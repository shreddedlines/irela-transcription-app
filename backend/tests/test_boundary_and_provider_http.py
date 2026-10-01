# -*- coding: utf-8 -*-
"""
The 60-minute boundary with encoder framing tolerance, and the provider
modules' HTTP classification through a mocked transport. No network.
"""
import asyncio
import json
import os
import sys

import httpx
import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import limits as audio_limits                        # noqa: E402
from providers import assemblyai, deepgram, errors   # noqa: E402

SHARED = os.path.join(os.path.dirname(__file__), "..", "..", "shared", "limits.json")


# ---- A. 60-minute boundary -----------------------------------------------------

@pytest.mark.parametrize("seconds,accepted", [
    (3599.0, True), (3599.9, True), (3600.0, True),
    (3600.1, True),    # AAC framing of a 3600.000 s recording (measured 3600.13-3600.16)
    (3600.9, False), (3601.0, False)])
def test_encoded_duration_boundary(seconds, accepted):
    if accepted:
        audio_limits.check_duration(seconds)
    else:
        with pytest.raises(audio_limits.LimitExceeded):
            audio_limits.check_duration(seconds)


def test_limits_parity_with_shared_definition():
    j = json.load(open(SHARED, encoding="utf-8"))
    assert audio_limits.MAX_DURATION_SECONDS == j["max_duration_seconds"] == 3600
    assert audio_limits.DURATION_FRAMING_TOLERANCE_SECONDS == j["encoded_duration_tolerance_seconds"] == 0.5
    assert audio_limits.MAX_UPLOAD_BYTES == j["max_upload_bytes"] == 104857600


def test_measured_device_encoding_of_a_full_hour_fits_the_tolerance():
    # From the device run: 3600.16 s of PCM encoded to 3600.32 s; a recording
    # capped at exactly 3600.000 s encodes to at most ~3600.16 s.
    audio_limits.check_duration(3600.32)


# ---- provider HTTP classification -------------------------------------------------

def run(coro):
    return asyncio.run(coro)


_REAL_ASYNC_CLIENT = httpx.AsyncClient


def mock_client(monkeypatch, module, handler):
    def factory(*a, **k):
        k["transport"] = httpx.MockTransport(handler)
        return _REAL_ASYNC_CLIENT(*a, **k)
    monkeypatch.setattr(module.httpx, "AsyncClient", factory)


@pytest.mark.parametrize("status,body,kind", [
    (401, {"err_code": "INVALID_AUTH"}, errors.CREDENTIAL),
    (402, {"err_code": "ASR_PAYMENT_REQUIRED"}, errors.QUOTA),
    (403, {"err_code": "INSUFFICIENT_PERMISSIONS"}, errors.CREDENTIAL),
    (429, {}, errors.RATE_LIMITED),
    (500, {}, errors.TRANSIENT),
    (400, {"err_code": "Bad Request"}, errors.INPUT)])
def test_deepgram_http_errors_are_classified(monkeypatch, status, body, kind):
    monkeypatch.setenv("DEEPGRAM_API_KEY", "dg-fake")
    seen = {}

    def handler(request):
        seen["mip"] = request.url.params.get("mip_opt_out")
        return httpx.Response(status, json=body, headers={"retry-after": "7"})
    mock_client(monkeypatch, deepgram, handler)
    with pytest.raises(errors.ProviderError) as e:
        run(deepgram.transcribe(b"AUDIO", "audio/wav", language="hi"))
    assert e.value.kind == kind
    assert seen["mip"] == "true"                   # invariant holds on every failed call too
    if kind == errors.RATE_LIMITED:
        assert e.value.retry_after_s == 7
    assert "dg-fake" not in e.value.message


def test_deepgram_timeout_is_transient(monkeypatch):
    monkeypatch.setenv("DEEPGRAM_API_KEY", "dg-fake")

    def handler(request):
        raise httpx.ReadTimeout("slow", request=request)
    mock_client(monkeypatch, deepgram, handler)
    with pytest.raises(errors.ProviderError) as e:
        run(deepgram.transcribe(b"AUDIO", "audio/wav"))
    assert e.value.kind == errors.TRANSIENT and e.value.retryable


def test_assemblyai_upload_credential_and_quota(monkeypatch):
    monkeypatch.setenv("ASSEMBLYAI_API_KEY", "aai-fake")
    for status, body, kind in [(401, {"error": "Invalid API key"}, errors.CREDENTIAL),
                               (402, {"error": "account balance"}, errors.QUOTA),
                               (429, {}, errors.RATE_LIMITED)]:
        mock_client(monkeypatch, assemblyai, lambda r, s=status, b=body: httpx.Response(s, json=b))
        with pytest.raises(errors.ProviderError) as e:
            run(assemblyai.transcribe(b"AUDIO", "audio/wav", language="hi"))
        assert e.value.kind == kind


def test_assemblyai_success_uses_frozen_model_and_language(monkeypatch):
    monkeypatch.setenv("ASSEMBLYAI_API_KEY", "aai-fake")
    sent = {}

    def handler(request):
        if request.url.path.endswith("/upload"):
            return httpx.Response(200, json={"upload_url": "https://cdn/x"})
        if request.method == "POST":
            sent.update(json.loads(request.content))
            return httpx.Response(200, json={"id": "t1"})
        return httpx.Response(200, json={"status": "completed", "text": "नमस्ते",
                                         "language_code": "hi", "audio_duration": 12})
    mock_client(monkeypatch, assemblyai, handler)
    out = run(assemblyai.transcribe(b"AUDIO", "audio/wav", language="hi"))
    assert out["provider"] == "assemblyai" and out["audio_duration_s"] == 12
    assert sent["speech_models"] == ["universal-3-5-pro"] and sent["language_code"] == "hi"


def test_assemblyai_job_error_is_input_not_failover(monkeypatch):
    monkeypatch.setenv("ASSEMBLYAI_API_KEY", "aai-fake")

    def handler(request):
        if request.url.path.endswith("/upload"):
            return httpx.Response(200, json={"upload_url": "https://cdn/x"})
        if request.method == "POST":
            return httpx.Response(200, json={"id": "t1"})
        return httpx.Response(200, json={"status": "error", "error": "unsupported audio"})
    mock_client(monkeypatch, assemblyai, handler)
    with pytest.raises(errors.ProviderError) as e:
        run(assemblyai.transcribe(b"AUDIO", "audio/wav"))
    assert e.value.kind == errors.INPUT


def test_assemblyai_polling_is_bounded(monkeypatch):
    monkeypatch.setenv("ASSEMBLYAI_API_KEY", "aai-fake")
    monkeypatch.setattr(assemblyai, "POLL_INTERVAL_S", 0.01)

    def handler(request):
        if request.url.path.endswith("/upload"):
            return httpx.Response(200, json={"upload_url": "https://cdn/x"})
        if request.method == "POST":
            return httpx.Response(200, json={"id": "t1"})
        return httpx.Response(200, json={"status": "queued"})
    mock_client(monkeypatch, assemblyai, handler)
    with pytest.raises(errors.ProviderError) as e:
        run(assemblyai.transcribe(b"AUDIO", "audio/wav", timeout_s=0.1))
    assert e.value.kind == errors.TRANSIENT


@pytest.mark.parametrize("module,key,status,verdict", [
    (deepgram, "DEEPGRAM_API_KEY", 200, "ok"), (deepgram, "DEEPGRAM_API_KEY", 401, "credential"),
    (deepgram, "DEEPGRAM_API_KEY", 404, "inconclusive"),
    (assemblyai, "ASSEMBLYAI_API_KEY", 200, "ok"), (assemblyai, "ASSEMBLYAI_API_KEY", 403, "credential")])
def test_probes_are_get_only_and_classified(monkeypatch, module, key, status, verdict):
    monkeypatch.setenv(key, "fake")
    methods = []

    def handler(request):
        methods.append(request.method)
        return httpx.Response(status, json={})
    mock_client(monkeypatch, module, handler)
    assert run(module.probe()) == verdict
    assert methods == ["GET"]                      # never uploads audio, never bills
