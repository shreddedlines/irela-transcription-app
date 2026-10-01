# -*- coding: utf-8 -*-
"""
Phase C: kill switch, enforced limits, metering, idempotency.

No credential and no network: the provider module is stubbed, and the recurring
assertion is `calls["n"]` -- every control here exists to stop a provider
request, so counting requests is the only thing that proves it works.
"""
import importlib
import os
import sys
import tempfile

import pytest
from fastapi.testclient import TestClient

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import idempotency as idem_mod   # noqa: E402
import limits as audio_limits    # noqa: E402


@pytest.fixture()
def client(monkeypatch):
    """Fresh app per test, with a throwaway metering DB and a stub provider."""
    monkeypatch.setenv("METERING_DB", os.path.join(tempfile.mkdtemp(), "m.sqlite3"))
    monkeypatch.setenv("TRANSCRIPTION_ENABLED", "true")
    monkeypatch.setenv("CLIENT_REQUESTS_PER_MINUTE", "1000")
    monkeypatch.setenv("CLIENT_TRANSCRIPTIONS_PER_DAY", "1000")
    monkeypatch.setenv("DEEPGRAM_API_KEY", "test-key-not-real")
    # These tests exercise the byte/duration caps (up to 60 min per request),
    # not the free-tier limits, which have their own tests. (A real 60:00 AAC
    # probes as 3600.05 s, just over the default 60 min/day network cap.)
    monkeypatch.setenv("FREE_MONTHLY_SECONDS", "1000000")
    monkeypatch.setenv("FREE_DAILY_NETWORK_SECONDS", "1000000")
    monkeypatch.setenv("FREE_DAILY_BUDGET_INR", "100000")

    import app as app_mod
    importlib.reload(app_mod)

    calls = {"n": 0}

    async def fake_transcribe(audio, content_type, language=None,
                              keyterms=None, timeout_s=300.0):
        calls["n"] += 1
        return {"text": "hello", "provider": "deepgram",
                "detected_language": "hi", "provider_ms": 12}

    monkeypatch.setattr(app_mod.deepgram, "transcribe", fake_transcribe)
    c = TestClient(app_mod.app)
    # Client auth is always on; these tests exercise other controls,
    # so they run as one registered installation (see test_client_auth.py).
    c.headers["Authorization"] = "Bearer " + c.post("/v1/installations").json()["token"]
    c.calls = calls
    c.app_mod = app_mod
    return c


def wav(seconds: float = 1.0, rate: int = 16000) -> bytes:
    """Minimal real RIFF/WAV so the duration probe has something to read."""
    n = int(seconds * rate) * 2
    return (b"RIFF" + (36 + n).to_bytes(4, "little") + b"WAVEfmt " +
            (16).to_bytes(4, "little") + (1).to_bytes(2, "little") +
            (1).to_bytes(2, "little") + rate.to_bytes(4, "little") +
            (rate * 2).to_bytes(4, "little") + (2).to_bytes(2, "little") +
            (16).to_bytes(2, "little") + b"data" + n.to_bytes(4, "little") +
            b"\x00" * n)


def post(c, data=None, headers=None, audio=None):
    return c.post("/v1/transcribe",
                  files={"file": ("clip.wav", audio or wav(), "audio/wav")},
                  data=data or {"provider": "deepgram"},
                  headers=headers or {})


# ---------------------------------------------------------------- kill switch
def test_kill_switch_blocks_provider_entirely(client, monkeypatch):
    monkeypatch.setenv("TRANSCRIPTION_ENABLED", "false")
    r = post(client)
    assert r.status_code == 503
    assert r.json()["retryable"] is False        # client must NOT retry-loop
    assert r.json()["reason"] == "disabled"
    assert client.calls["n"] == 0                # no spend


def test_kill_switch_accepts_several_falsey_spellings(client, monkeypatch):
    for value in ("false", "0", "no", "off", "FALSE", " Off "):
        monkeypatch.setenv("TRANSCRIPTION_ENABLED", value)
        assert post(client).status_code == 503
    assert client.calls["n"] == 0


def test_kill_switch_default_is_enabled(client, monkeypatch):
    monkeypatch.delenv("TRANSCRIPTION_ENABLED", raising=False)
    assert post(client).status_code == 200
    assert client.calls["n"] == 1


def test_healthz_reports_switch_and_limits(client):
    j = client.get("/healthz").json()
    assert j["transcription_enabled"] is True
    assert j["idempotency_shared"] is False       # honest about the limitation
    assert j["limits"]["max_duration_seconds"] == audio_limits.MAX_DURATION_SECONDS


# -------------------------------------------------------------------- limits
def test_oversized_upload_rejected_server_side(client):
    big = b"R" * (audio_limits.MAX_UPLOAD_BYTES + 1)
    r = post(client, audio=big)
    assert r.status_code == 413
    assert r.json()["retryable"] is False         # too big stays too big
    assert client.calls["n"] == 0


def test_over_duration_audio_rejected_server_side(client):
    long_wav = wav(seconds=audio_limits.MAX_DURATION_SECONDS + 10, rate=8)
    r = post(client, audio=long_wav)
    assert r.status_code == 413
    assert "minute" in r.json()["error"]
    assert client.calls["n"] == 0


def test_duration_limit_is_sixty_minutes():
    assert audio_limits._load()["max_duration_seconds"] == 3600
    assert audio_limits.MAX_DURATION_SECONDS == 3600
    assert audio_limits.MAX_UPLOAD_BYTES == 100 * 1024 * 1024     # unchanged


@pytest.mark.parametrize("seconds, expected, calls", [
    (59 * 60 + 59, 200, 1),     # 59:59 -> accepted
    (60 * 60, 200, 1),          # 60:00 exactly -> accepted
    (60 * 60 + 1, 413, 0),      # 60:01 -> rejected before the provider
])
def test_sixty_minute_boundary(client, seconds, expected, calls):
    r = post(client, audio=wav(seconds=seconds, rate=8))
    assert r.status_code == expected
    assert client.calls["n"] == calls
    if expected == 413:
        assert r.json()["retryable"] is False
        assert r.json()["error"] == ("That recording is longer than 60 minutes. "
                                     "Try splitting it into shorter parts.")


def test_real_sixty_minute_aac_framing_is_accepted(client):
    """ffmpeg's 60:00 ADTS AAC probes as 3600.045 s (1024-sample frames plus
    encoder priming). Framing must not turn a 60-minute file into a rejection,
    while a genuine 60:01 still is."""
    sys.path.insert(0, os.path.dirname(__file__))
    from test_probe_and_logging_guards import adts
    sixty = adts(3600.045, rate_idx=4, rate=44100)
    assert 3600.0 < audio_limits.probe_duration_seconds(sixty, "audio/aac") < 3600.1
    r = client.post("/v1/transcribe", files={"file": ("a.aac", sixty, "audio/aac")},
                    data={"provider": "deepgram"})
    assert r.status_code == 200 and client.calls["n"] == 1

    over = adts(3601.04, rate_idx=4, rate=44100)
    r = client.post("/v1/transcribe", files={"file": ("b.aac", over, "audio/aac")},
                    data={"provider": "deepgram"})
    assert r.status_code == 413 and client.calls["n"] == 1


def test_empty_audio_rejected(client):
    r = client.post("/v1/transcribe",
                    files={"file": ("e.wav", b"", "audio/wav")},
                    data={"provider": "deepgram"})
    assert r.status_code == 422
    assert client.calls["n"] == 0


def test_within_limits_reaches_provider(client):
    assert post(client).status_code == 200
    assert client.calls["n"] == 1


def test_env_override_may_only_tighten(monkeypatch):
    monkeypatch.setenv("MAX_UPLOAD_BYTES", str(10 ** 12))   # try to widen
    importlib.reload(audio_limits)
    try:
        shared = audio_limits._load()["max_upload_bytes"]
        assert audio_limits.MAX_UPLOAD_BYTES == shared       # widening ignored
    finally:
        monkeypatch.delenv("MAX_UPLOAD_BYTES", raising=False)
        importlib.reload(audio_limits)


# --------------------------------------------------------------- idempotency
def test_same_key_replays_without_a_second_provider_call(client):
    h = {"Idempotency-Key": "job-1"}
    first = post(client, headers=h)
    second = post(client, headers=h)
    assert first.status_code == second.status_code == 200
    assert second.json().get("replayed") is True
    assert second.json()["text"] == first.json()["text"]
    assert client.calls["n"] == 1                 # billed once


def test_different_keys_are_billed_separately(client):
    post(client, headers={"Idempotency-Key": "job-a"})
    post(client, headers={"Idempotency-Key": "job-b"})
    assert client.calls["n"] == 2


def test_missing_key_is_not_cached(client):
    post(client)
    post(client)
    assert client.calls["n"] == 2


def test_in_process_store_declares_it_is_not_shared():
    s = idem_mod.InProcessIdempotencyStore()
    assert s.is_shared is False                   # deployment guard
    s.put("k", {"text": "x"})
    assert s.get("k") == {"text": "x"}
    assert s.get("absent") is None


def test_in_process_store_expires_entries():
    s = idem_mod.InProcessIdempotencyStore(ttl_seconds=0)
    s.put("k", {"text": "x"})
    assert s.get("k") is None


# ------------------------------------------------------------------ metering
def test_successful_request_is_metered_without_transcript_text(client):
    post(client, headers={"Idempotency-Key": "m-1"})
    t = client.app_mod.meter.totals()
    assert t["requests"] == 1
    assert t["successes"] == 1
    assert t["audio_seconds"] > 0
    assert t["by_provider"] == {"deepgram": 1}

    # The stored row must carry a length, never the text itself.
    import sqlite3
    con = sqlite3.connect(os.environ["METERING_DB"])
    cols = [r[1] for r in con.execute("PRAGMA table_info(requests)")]
    assert "chars" in cols
    assert not any(c in cols for c in ("text", "transcript", "content"))
    row = con.execute("SELECT chars FROM requests").fetchone()
    assert row[0] == len("hello")
    blob = " ".join(str(x) for x in con.execute("SELECT * FROM requests").fetchone())
    assert "hello" not in blob


def test_failures_and_rejections_are_metered(client, monkeypatch):
    post(client, audio=b"R" * (audio_limits.MAX_UPLOAD_BYTES + 1))   # 413
    monkeypatch.setenv("TRANSCRIPTION_ENABLED", "false")
    post(client)                                                     # 503
    t = client.app_mod.meter.totals()
    assert t["requests"] == 2
    assert t["failures"] == 2
    assert t["successes"] == 0


def test_replays_are_metered_at_zero_cost(client):
    h = {"Idempotency-Key": "m-2"}
    post(client, headers=h)
    post(client, headers=h)
    t = client.app_mod.meter.totals()
    assert t["requests"] == 2
    assert t["replayed"] == 1
    # Only the real call carries cost.
    assert t["est_cost_inr"] == pytest.approx(
        audio_limits.estimated_cost_inr(1.0), rel=0.01)


def test_usage_endpoint_exposes_totals(client):
    post(client)
    j = client.get("/v1/usage").json()
    assert j["requests"] == 1
    assert "est_cost_inr" in j


def test_metering_survives_restart(client):
    post(client)
    reopened = client.app_mod.Metering(os.environ["METERING_DB"])
    assert reopened.totals()["requests"] == 1


# ------------------------------------------------- provider call-count bound
def test_provider_never_called_when_any_gate_rejects(client, monkeypatch):
    post(client, audio=b"R" * (audio_limits.MAX_UPLOAD_BYTES + 1))
    client.post("/v1/transcribe",
                files={"file": ("e.wav", b"", "audio/wav")},
                data={"provider": "deepgram"})
    monkeypatch.setenv("TRANSCRIPTION_ENABLED", "false")
    post(client)
    assert client.calls["n"] == 0
