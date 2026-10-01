# -*- coding: utf-8 -*-
"""
Enforced audio limits, loaded from the authoritative shared definition.

The backend is the SECURITY boundary. The Android client checks the same limits
first so the user gets an immediate answer without a pointless upload, but that
check is a courtesy: anything in an installed app can be modified, so every
request is re-checked here before a provider is ever called.
"""

import json
import os
from pathlib import Path

_SHARED = Path(__file__).resolve().parent.parent / "shared" / "limits.json"


def _load() -> dict:
    with open(_SHARED, encoding="utf-8") as fh:
        return json.load(fh)


_L = _load()

VERSION = _L["version"]
MAX_DURATION_SECONDS = int(_L["max_duration_seconds"])
MAX_UPLOAD_BYTES = int(_L["max_upload_bytes"])
EST_COST_INR_PER_HOUR = float(_L["estimated_cost_inr_per_hour"])

# Env overrides exist for staging, but may only TIGHTEN the shared limit --
# a deployment must not be able to widen the cost ceiling by accident.
MAX_UPLOAD_BYTES = min(
    MAX_UPLOAD_BYTES, int(os.environ.get("MAX_UPLOAD_BYTES", MAX_UPLOAD_BYTES)))
MAX_DURATION_SECONDS = min(
    MAX_DURATION_SECONDS,
    int(os.environ.get("MAX_AUDIO_SECONDS", MAX_DURATION_SECONDS)))


class LimitExceeded(Exception):
    def __init__(self, status: int, message: str):
        super().__init__(message)
        self.status = status
        self.message = message


def check_bytes(n: int) -> None:
    if n <= 0:
        raise LimitExceeded(422, "empty audio")
    if n > MAX_UPLOAD_BYTES:
        raise LimitExceeded(
            413, f"audio exceeds {MAX_UPLOAD_BYTES // (1024 * 1024)} MB limit")


# Container framing, not speech. AAC codes whole 1024-sample frames and
# encoders prepend priming samples, so a real 60:00 ADTS file probes as
# 3600.045 s and the Android encoder's 60:00 M4A as 3600.13-3600.16 s
# (measured). Half a second admits every real 60:00 file while 60:01 is still
# rejected. Shared with the app, which checks its encoded file against it.
DURATION_FRAMING_TOLERANCE_SECONDS = float(_L["encoded_duration_tolerance_seconds"])


def check_duration(seconds: float) -> None:
    """Only meaningful when the duration is known; <= 0 means unknown."""
    if seconds and seconds > MAX_DURATION_SECONDS + DURATION_FRAMING_TOLERANCE_SECONDS:
        # Shown to the user verbatim by the app (terminal input failure), so it
        # matches the client's own wording in AudioLimits.message.
        raise LimitExceeded(
            413,
            f"That recording is longer than {MAX_DURATION_SECONDS // 60} minutes. "
            "Try splitting it into shorter parts.")


def estimated_cost_inr(duration_seconds: float) -> float:
    """Rough provider cost for metering. Deliberately an estimate, not a bill."""
    if not duration_seconds or duration_seconds <= 0:
        return 0.0
    return round(EST_COST_INR_PER_HOUR * duration_seconds / 3600.0, 4)


def probe_duration_seconds(audio: bytes, content_type: str) -> float:
    """
    Best-effort duration without a decoder dependency.

    Exact arithmetic, no decoder, for the containers the app actually sends:
    RIFF/WAV (recorder), Ogg Opus/Vorbis (WhatsApp voice notes), ADTS AAC
    (WhatsApp "audio" shares) and ISO-BMFF MP4/M4A/3GP (phone recorders).

    ADTS and MP4 were added after testing found WhatsApp AAC returned 0.0 --
    which silently disabled the duration cap for the most common input and
    metered it at zero cost.

    Returns 0.0 when unknown rather than guessing: an unknown duration must not
    become a false rejection. The byte cap still bounds cost, and app.py meters
    such requests from the provider-reported duration instead.
    """
    try:
        if audio[:4] == b"RIFF" and audio[8:12] == b"WAVE":
            i = 12
            rate = channels = bits = 0
            while i + 8 <= len(audio):
                cid = audio[i:i + 4]
                size = int.from_bytes(audio[i + 4:i + 8], "little")
                if cid == b"fmt ":
                    channels = int.from_bytes(audio[i + 10:i + 12], "little")
                    rate = int.from_bytes(audio[i + 12:i + 16], "little")
                    bits = int.from_bytes(audio[i + 22:i + 24], "little")
                elif cid == b"data" and rate and channels and bits:
                    return size / float(rate * channels * (bits // 8))
                i += 8 + size + (size & 1)
            return 0.0

        adts = _adts_duration_seconds(audio)
        if adts > 0:
            return adts

        mp4 = _mp4_duration_seconds(audio)
        if mp4 > 0:
            return mp4

        if audio[:4] == b"OggS":
            # Granule position of the last page, in 48 kHz units for Opus.
            last = audio.rfind(b"OggS")
            if last >= 0 and last + 14 <= len(audio):
                granule = int.from_bytes(audio[last + 6:last + 14], "little")
                pre = 0
                h = audio.find(b"OpusHead")
                if h >= 0 and h + 12 <= len(audio):
                    pre = int.from_bytes(audio[h + 10:h + 12], "little")
                if 0 < granule < (1 << 62):
                    return max(0.0, (granule - pre) / 48000.0)
            return 0.0
    except Exception:
        return 0.0
    return 0.0


# ADTS sampling_frequency_index -> Hz (ISO/IEC 14496-3, Table 1.18).
_ADTS_RATES = (96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050,
               16000, 12000, 11025, 8000, 7350)


def _skip_id3(audio: bytes) -> int:
    """Offset past a leading ID3v2 tag, which some encoders prepend to ADTS."""
    if audio[:3] == b"ID3" and len(audio) >= 10:
        size = ((audio[6] & 0x7F) << 21) | ((audio[7] & 0x7F) << 14) | \
               ((audio[8] & 0x7F) << 7) | (audio[9] & 0x7F)
        footer = 10 if audio[5] & 0x10 else 0
        return 10 + size + footer
    return 0


def _adts_duration_seconds(audio: bytes) -> float:
    """
    Walks ADTS frames: each header gives its own length and its raw data block
    count, and every raw data block is exactly 1024 samples. Exact, no decode.

    Requires at least a few consecutive valid frames before trusting the
    result, so arbitrary bytes that happen to start 0xFFF are not mistaken for
    a long AAC stream. Stops at the first lost sync (e.g. trailing garbage).
    """
    i = _skip_id3(audio)
    n = len(audio)
    samples = 0
    rate = 0
    frames = 0
    while i + 7 <= n:
        if audio[i] != 0xFF or (audio[i + 1] & 0xF6) != 0xF0:   # sync + layer 00
            break
        rate_idx = (audio[i + 2] >> 2) & 0x0F
        if rate_idx >= len(_ADTS_RATES):
            break
        frame_len = ((audio[i + 3] & 0x03) << 11) | (audio[i + 4] << 3) | (audio[i + 5] >> 5)
        if frame_len < 7:
            break
        if rate == 0:
            rate = _ADTS_RATES[rate_idx]
        elif _ADTS_RATES[rate_idx] != rate:
            break
        blocks = (audio[i + 6] & 0x03) + 1
        samples += 1024 * blocks
        frames += 1
        i += frame_len
    if frames < 3 or rate == 0:
        return 0.0
    return samples / float(rate)


def _mp4_duration_seconds(audio: bytes) -> float:
    """
    Reads duration/timescale from the movie header ('moov' > 'mvhd') of an
    ISO-BMFF file. Only top-level boxes are walked, so 'moov' is found whether
    it is written before or after the media data.
    """
    n = len(audio)
    if n < 12 or audio[4:8] != b"ftyp":
        return 0.0

    def boxes(start, end):
        j = start
        while j + 8 <= end:
            size = int.from_bytes(audio[j:j + 4], "big")
            kind = audio[j + 4:j + 8]
            header = 8
            if size == 1 and j + 16 <= end:
                size = int.from_bytes(audio[j + 8:j + 16], "big")
                header = 16
            elif size == 0:
                size = end - j
            if size < header or j + size > end:
                return
            yield kind, j + header, j + size
            j += size

    for kind, body, box_end in boxes(0, n):
        if kind != b"moov":
            continue
        for inner, ibody, _ in boxes(body, box_end):
            if inner != b"mvhd" or ibody + 4 > n:
                continue
            version = audio[ibody]
            p = ibody + 4
            if version == 1 and p + 28 <= n:
                timescale = int.from_bytes(audio[p + 16:p + 20], "big")
                duration = int.from_bytes(audio[p + 20:p + 28], "big")
            elif version == 0 and p + 16 <= n:
                timescale = int.from_bytes(audio[p + 8:p + 12], "big")
                duration = int.from_bytes(audio[p + 12:p + 16], "big")
            else:
                return 0.0
            if timescale <= 0 or duration in (0, 0xFFFFFFFF, 0xFFFFFFFFFFFFFFFF):
                return 0.0
            return duration / float(timescale)
    return 0.0
