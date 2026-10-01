# -*- coding: utf-8 -*-
"""
`/v1/usage` end to end: the fields an operator actually reads, and the
guarantee that alerting can never break a transcription.
"""

import importlib
import logging
import os
import sys
import tempfile

import pytest
from fastapi.testclient import TestClient

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))


@pytest.fixture()
def client(monkeypatch):
    monkeypatch.setenv("METERING_DB", os.path.join(tempfile.mkdtemp(), "m.sqlite3"))
    monkeypatch.setenv("TRANSCRIPTION_ENABLED", "true")
    monkeypatch.setenv("CLIENT_REQUESTS_PER_MINUTE", "1000")
    monkeypatch.setenv("CLIENT_TRANSCRIPTIONS_PER_DAY", "1000")
    monkeypatch.setenv("DEEPGRAM_API_KEY", "test-key-not-real")
    monkeypatch.delenv("USAGE_ALERT_INR", raising=False)

    import app as app_mod
    importlib.reload(app_mod)

    async def fake_transcribe(audio, content_type, language=None,
                              keyterms=None, timeout_s=300.0):
        return {"text": "hello", "provider": "deepgram",
                "detected_language": "hi", "provider_ms": 12}

    monkeypatch.setattr(app_mod.deepgram, "transcribe", fake_transcribe)
    c = TestClient(app_mod.app)
    # Client auth is always on; these tests exercise other controls,
    # so they run as one registered installation (see test_client_auth.py).
    c.headers["Authorization"] = "Bearer " + c.post("/v1/installations").json()["token"]
    c.app_mod = app_mod
    return c


def wav(seconds: float = 60.0, rate: int = 16000) -> bytes:
    n = int(seconds * rate) * 2
    return (b"RIFF" + (36 + n).to_bytes(4, "little") + b"WAVEfmt " +
            (16).to_bytes(4, "little") + (1).to_bytes(2, "little") +
            (1).to_bytes(2, "little") + rate.to_bytes(4, "little") +
            (rate * 2).to_bytes(4, "little") + (2).to_bytes(2, "little") +
            (16).to_bytes(2, "little") + b"data" + n.to_bytes(4, "little") +
            b"\x00" * n)


def post(c, key="k-1"):
    return c.post("/v1/transcribe",
                  files={"file": ("a.wav", wav(), "audio/wav")},
                  data={"provider": "deepgram"},
                  headers={"Idempotency-Key": key})


def test_usage_reports_the_alert_fields(client):
    body = client.get("/v1/usage").json()
    for field in ("alert_threshold_inr", "alert_triggered",
                  "alert_window_hours", "alert_window_est_cost_inr"):
        assert field in body, f"missing {field}"
    assert body["alert_threshold_inr"] is None
    assert body["alert_triggered"] is False


def test_usage_never_reports_transcript_text(client):
    assert post(client).status_code == 200
    body = client.get("/v1/usage").json()
    serialized = str(body).lower()
    assert "hello" not in serialized
    assert "text" not in body


def test_crossing_the_threshold_is_logged_during_a_real_request(client, monkeypatch, caplog):
    # A tiny threshold so one minute of audio clears it.
    monkeypatch.setenv("USAGE_ALERT_INR", "0.01")
    with caplog.at_level(logging.WARNING, logger="proxy.usage"):
        assert post(client, "k-a").status_code == 200
    assert any(r.getMessage().startswith("USAGE_ALERT est")
               for r in caplog.records), "operator gets no durable signal"

    body = client.get("/v1/usage").json()
    assert body["alert_triggered"] is True
    assert body["est_cost_inr"] > 0


def test_the_alert_window_is_independent_of_the_requested_window(client, monkeypatch):
    monkeypatch.setenv("USAGE_ALERT_INR", "0.01")
    assert post(client, "k-b").status_code == 200
    # Asking to SEE the last 6 minutes must not report "not triggered" for a
    # breach the configured 24-hour window still holds.
    body = client.get("/v1/usage?since_hours=0.1").json()
    assert body["alert_triggered"] is True


def test_an_alerting_failure_cannot_fail_a_transcription(client, monkeypatch):
    def boom(*a, **k):
        raise RuntimeError("bookkeeping exploded")

    monkeypatch.setattr(client.app_mod.usage_alert, "evaluate", boom)
    # The user's transcription succeeded; accounting is not their problem.
    r = post(client, "k-c")
    assert r.status_code == 200
    assert r.json()["text"] == "hello"
