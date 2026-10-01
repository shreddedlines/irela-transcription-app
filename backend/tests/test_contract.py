# -*- coding: utf-8 -*-
"""
Backend contract tests: does the proxy forward the FROZEN Deepgram config, and
can Android influence anything it must not?

httpx is stubbed so no credential and no network are needed.
"""
import os, sys
import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
from providers import deepgram  # noqa: E402


class _Resp:
    status_code = 200
    def json(self):
        return {"results": {"channels": [{"alternatives": [
            {"transcript": "hello", "languages": ["hi"]}]}]},
            "metadata": {"duration": 1.0}}


class _Client:
    """Captures the outgoing request instead of sending it."""
    seen = {}
    def __init__(self, *a, **k): pass
    async def __aenter__(self): return self
    async def __aexit__(self, *a): return False
    async def post(self, url, params=None, headers=None, content=None):
        _Client.seen = {"url": url, "params": params, "headers": headers,
                        "content": content}
        return _Resp()


@pytest.fixture(autouse=True)
def _stub(monkeypatch):
    monkeypatch.setenv("DEEPGRAM_API_KEY", "test-key-not-real")
    monkeypatch.setattr(deepgram.httpx, "AsyncClient", _Client)
    _Client.seen = {}


async def _call(**kw):
    return await deepgram.transcribe(audio=b"AUDIO", content_type="audio/ogg", **kw)


@pytest.mark.asyncio
async def test_frozen_configuration_is_forwarded_verbatim():
    await _call()
    p = _Client.seen["params"]
    assert p["model"] == "nova-3"
    assert p["language"] == "hi"
    assert p["smart_format"] == "false"
    assert p["punctuate"] == "true"
    assert p["numerals"] == "true"
    assert p["mip_opt_out"] == "true"


@pytest.mark.asyncio
async def test_keyterms_are_forwarded_as_repeated_values():
    await _call(keyterms=["Venkatesh", "Priyanka Deshmukh"])
    assert _Client.seen["params"]["keyterm"] == ["Venkatesh", "Priyanka Deshmukh"]


@pytest.mark.asyncio
async def test_mip_opt_out_present_on_every_call_shape():
    for kw in ({}, {"language": "hi"}, {"keyterms": ["x"]},
               {"language": "en", "keyterms": ["a", "b"]}):
        _Client.seen = {}
        await _call(**kw)
        assert _Client.seen["params"]["mip_opt_out"] == "true"


@pytest.mark.asyncio
async def test_credential_comes_from_environment_only():
    await _call()
    auth = _Client.seen["headers"]["Authorization"]
    assert auth == "Token test-key-not-real"
    # and it is never echoed into the query string
    assert "test-key-not-real" not in str(_Client.seen["params"])


@pytest.mark.asyncio
async def test_client_cannot_inject_deepgram_parameters():
    """
    The proxy's public surface accepts only `language` and `keyterm`. There is
    no code path from an Android field to any other Deepgram parameter, which
    is what keeps mip_opt_out out of a client's reach.
    """
    import inspect
    sig = inspect.signature(deepgram.transcribe)
    assert set(sig.parameters) == {
        "audio", "content_type", "language", "keyterms", "timeout_s"}


@pytest.mark.asyncio
async def test_audio_is_sent_as_body_not_reencoded():
    await _call()
    assert _Client.seen["content"] == b"AUDIO"
    assert _Client.seen["headers"]["Content-Type"] == "audio/ogg"
