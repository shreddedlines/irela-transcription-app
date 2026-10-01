# -*- coding: utf-8 -*-
"""
Regression tests for two backend defects found before release.

1. The duration cap did not apply to ADTS AAC (WhatsApp "audio" shares) or
   MP4/M4A: the probe returned 0.0, the cap was skipped, and the request was
   metered at zero cost -- so /v1/usage and the spend alert never saw it.
2. httpx logged every Deepgram request URL at INFO, keyterms included.

No credential and no network: the provider is stubbed or served by a mock
transport, and the recurring assertion is whether a provider call happened.
"""
import asyncio
import importlib
import io
import logging
import os
import sys
import tempfile

import httpx
import pytest
from fastapi.testclient import TestClient

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import limits as audio_limits   # noqa: E402


# ---- fixtures -------------------------------------------------------------

def adts(seconds: float, rate_idx: int = 8, rate: int = 16000, id3: bool = False) -> bytes:
    """A real ADTS AAC stream: frames with 1024 samples each at `rate`."""
    frames = int(round(seconds * rate / 1024.0))
    frame_len = 16
    header = bytes([
        0xFF, 0xF1,
        (1 << 6) | (rate_idx << 2),                     # AAC-LC, rate index
        (frame_len >> 11) & 0x03,
        (frame_len >> 3) & 0xFF,
        ((frame_len << 5) & 0xE0) | 0x1F,
        0xFC,                                           # 1 raw data block
    ])
    body = (header + b"\x00" * (frame_len - 7)) * frames
    if id3:
        tag = b"\x00" * 20
        body = b"ID3\x04\x00\x00" + bytes([0, 0, 0, len(tag)]) + tag + body
    return body


def mp4(seconds: float, timescale: int = 44100, version: int = 0,
        moov_last: bool = False) -> bytes:
    def box(kind: bytes, payload: bytes) -> bytes:
        return (8 + len(payload)).to_bytes(4, "big") + kind + payload
    if version == 0:
        mvhd = bytes([0, 0, 0, 0]) + b"\x00" * 8 + timescale.to_bytes(4, "big") + \
               int(seconds * timescale).to_bytes(4, "big") + b"\x00" * 80
    else:
        mvhd = bytes([1, 0, 0, 0]) + b"\x00" * 16 + timescale.to_bytes(4, "big") + \
               int(seconds * timescale).to_bytes(8, "big") + b"\x00" * 80
    ftyp = box(b"ftyp", b"M4A \x00\x00\x00\x00M4A mp42")
    moov = box(b"moov", box(b"mvhd", mvhd))
    mdat = box(b"mdat", b"\x00" * 64)
    return ftyp + (mdat + moov if moov_last else moov + mdat)


@pytest.fixture()
def client(monkeypatch):
    monkeypatch.setenv("METERING_DB", os.path.join(tempfile.mkdtemp(), "m.sqlite3"))
    monkeypatch.setenv("TRANSCRIPTION_ENABLED", "true")
    monkeypatch.setenv("CLIENT_REQUESTS_PER_MINUTE", "1000")
    monkeypatch.setenv("CLIENT_TRANSCRIPTIONS_PER_DAY", "1000")
    monkeypatch.setenv("DEEPGRAM_API_KEY", "test-key-not-real")
    import app as app_mod
    importlib.reload(app_mod)
    calls = {"n": 0, "provider_duration": 0.0}

    async def fake_transcribe(audio, content_type, language=None,
                              keyterms=None, timeout_s=300.0):
        calls["n"] += 1
        return {"text": "hello", "provider": "deepgram", "detected_language": "hi",
                "provider_ms": 0, "audio_duration_s": calls["provider_duration"]}

    monkeypatch.setattr(app_mod.deepgram, "transcribe", fake_transcribe)
    c = TestClient(app_mod.app)
    # Client auth is always on; these tests exercise other controls,
    # so they run as one registered installation (see test_client_auth.py).
    c.headers["Authorization"] = "Bearer " + c.post("/v1/installations").json()["token"]
    c.calls = calls
    return c


def post(c, audio: bytes, content_type: str):
    return c.post("/v1/transcribe",
                  files={"file": ("a", audio, content_type)},
                  data={"provider": "deepgram"})


# ---- 1a. duration probes ---------------------------------------------------

@pytest.mark.parametrize("seconds", [1.0, 232.0, 1799.0])
def test_adts_duration_is_exact(seconds):
    got = audio_limits.probe_duration_seconds(adts(seconds), "audio/aac")
    assert abs(got - seconds) < 0.1


def test_adts_after_an_id3_tag():
    assert abs(audio_limits.probe_duration_seconds(adts(60, id3=True), "audio/aac") - 60) < 0.1


def test_adts_at_another_sample_rate():
    got = audio_limits.probe_duration_seconds(adts(90, rate_idx=4, rate=44100), "audio/aac")
    assert abs(got - 90) < 0.1


def test_too_few_frames_or_garbage_is_unknown_not_a_guess():
    assert audio_limits.probe_duration_seconds(adts(0.1)[:32], "audio/aac") == 0.0
    assert audio_limits.probe_duration_seconds(bytes(range(256)) * 40, "audio/aac") == 0.0
    assert audio_limits.probe_duration_seconds(b"\xff\xf1" + b"\x00" * 50, "audio/aac") == 0.0


@pytest.mark.parametrize("version", [0, 1])
@pytest.mark.parametrize("moov_last", [False, True])
def test_mp4_duration_from_mvhd(version, moov_last):
    got = audio_limits.probe_duration_seconds(
        mp4(1234.5, version=version, moov_last=moov_last), "audio/mp4")
    assert abs(got - 1234.5) < 0.01


def test_existing_probes_unchanged():
    ogg = b"OggS" + b"\x00" * 2 + (48000 * 10).to_bytes(8, "little") + b"\x00" * 20
    assert abs(audio_limits.probe_duration_seconds(ogg, "audio/ogg") - 10) < 0.01


# ---- 1b. the cap now applies, before any provider call --------------------

def test_long_whatsapp_aac_is_rejected_before_the_provider(client):
    r = post(client, adts(audio_limits.MAX_DURATION_SECONDS + 1), "audio/aac")
    assert r.status_code == 413
    assert r.json()["retryable"] is False
    assert client.calls["n"] == 0


def test_long_m4a_is_rejected_before_the_provider(client):
    r = post(client, mp4(audio_limits.MAX_DURATION_SECONDS + 1), "audio/mp4")
    assert r.status_code == 413
    assert client.calls["n"] == 0


def test_normal_whatsapp_aac_still_goes_through_and_is_metered(client):
    r = post(client, adts(232), "audio/aac")
    assert r.status_code == 200
    assert client.calls["n"] == 1
    usage = client.get("/v1/usage").json()
    assert abs(usage["audio_seconds"] - 232) < 0.5
    assert usage["est_cost_inr"] > 0


# ---- 1c. an unprobeable container is metered from the provider -----------

def test_unprobeable_audio_is_metered_from_provider_duration(client):
    client.calls["provider_duration"] = 600.0          # what Deepgram reports
    r = post(client, b"ID3\x04\x00\x00\x00\x00\x00\x00" + b"\x55" * 4000, "audio/mpeg")
    assert r.status_code == 200
    usage = client.get("/v1/usage").json()
    assert usage["audio_seconds"] == 600.0
    expected = audio_limits.estimated_cost_inr(600.0)
    assert usage["est_cost_inr"] == pytest.approx(expected)
    assert expected > 0


def test_spend_alert_sees_unprobeable_traffic(client, monkeypatch):
    monkeypatch.setenv("USAGE_ALERT_INR", "1")
    client.calls["provider_duration"] = 1790.0          # ~Rs 11
    post(client, b"\x55" * 5000, "application/octet-stream")
    assert client.get("/v1/usage").json()["alert_triggered"] is True


def test_probe_wins_when_provider_reports_less(client):
    client.calls["provider_duration"] = 1.0
    post(client, adts(300), "audio/aac")
    assert abs(client.get("/v1/usage").json()["audio_seconds"] - 300) < 0.5


def test_deepgram_adapter_returns_its_measured_duration(monkeypatch):
    import providers.deepgram as dg
    monkeypatch.setenv("DEEPGRAM_API_KEY", "test-key-not-real")

    def handler(request):
        return httpx.Response(200, json={
            "results": {"channels": [{"alternatives": [{"transcript": "x"}]}]},
            "metadata": {"duration": 232.4}})

    real = httpx.AsyncClient

    class Mocked(real):
        def __init__(self, *a, **k):
            k["transport"] = httpx.MockTransport(handler)
            super().__init__(*a, **k)

    monkeypatch.setattr(dg.httpx, "AsyncClient", Mocked)
    out = asyncio.run(dg.transcribe(b"\x00", "audio/aac"))
    assert out["audio_duration_s"] == 232.4


# ---- 2. no keyterms in logs -------------------------------------------------

def test_httpx_request_lines_are_not_logged_at_info(monkeypatch):
    import app as app_mod
    importlib.reload(app_mod)
    assert not logging.getLogger("httpx").isEnabledFor(logging.INFO)
    assert not logging.getLogger("httpcore").isEnabledFor(logging.INFO)


def test_a_real_request_with_keyterms_leaves_no_keyterm_in_the_log(monkeypatch):
    import app as app_mod
    importlib.reload(app_mod)
    import providers.deepgram as dg
    monkeypatch.setenv("DEEPGRAM_API_KEY", "dg_FAKE_KEY_FOR_TEST")

    sink = io.StringIO()
    handler_ = logging.StreamHandler(sink)
    logging.getLogger().addHandler(handler_)
    try:
        def respond(request):
            return httpx.Response(200, json={
                "results": {"channels": [{"alternatives": [{"transcript": "SECRET TEXT"}]}]},
                "metadata": {"duration": 3}})

        real = httpx.AsyncClient

        class Mocked(real):
            def __init__(self, *a, **k):
                k["transport"] = httpx.MockTransport(respond)
                super().__init__(*a, **k)

        monkeypatch.setattr(dg.httpx, "AsyncClient", Mocked)
        asyncio.run(dg.transcribe(b"\x00", "audio/aac", language="hi",
                                  keyterms=["Priyanka Deshmukh", "Venkatesh"]))
    finally:
        logging.getLogger().removeHandler(handler_)

    logged = sink.getvalue()
    for secret in ("Priyanka", "Deshmukh", "Venkatesh", "SECRET TEXT", "dg_FAKE_KEY"):
        assert secret not in logged, f"{secret!r} reached the log"
