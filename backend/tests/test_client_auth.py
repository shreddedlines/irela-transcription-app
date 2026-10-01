# -*- coding: utf-8 -*-
"""
Client authentication and abuse protection.

No credential and no network: the provider is a counting stub. The recurring
assertion is `calls["n"]` -- an unauthorized, rate-limited, over-quota or
over-budget request must never reach a provider -- and, for the middleware,
that the request body is never even read.
"""
import asyncio
import importlib
import json
import os
import sqlite3
import sys
import tempfile

import pytest
from fastapi.testclient import TestClient

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import client_auth   # noqa: E402


def wav(seconds: float = 1.0, rate: int = 16000) -> bytes:
    n = int(seconds * rate) * 2
    return (b"RIFF" + (36 + n).to_bytes(4, "little") + b"WAVEfmt " +
            (16).to_bytes(4, "little") + (1).to_bytes(2, "little") +
            (1).to_bytes(2, "little") + rate.to_bytes(4, "little") +
            (rate * 2).to_bytes(4, "little") + (2).to_bytes(2, "little") +
            (16).to_bytes(2, "little") + b"data" + n.to_bytes(4, "little") + b"\x00" * n)


def make_client(monkeypatch, **limits):
    db = os.path.join(tempfile.mkdtemp(), "m.sqlite3")
    monkeypatch.setenv("METERING_DB", db)
    monkeypatch.setenv("TRANSCRIPTION_ENABLED", "true")
    monkeypatch.setenv("DEEPGRAM_API_KEY", "dg-provider-key-not-real")
    for k in ("CLIENT_REQUESTS_PER_MINUTE", "CLIENT_TRANSCRIPTIONS_PER_DAY",
              "REGISTRATIONS_PER_IP_PER_HOUR", "REGISTRATIONS_PER_HOUR", "DAILY_BUDGET_INR"):
        monkeypatch.delenv(k, raising=False)
    for k, v in limits.items():
        monkeypatch.setenv(k, str(v))
    import app as app_mod
    importlib.reload(app_mod)
    calls = {"n": 0}

    async def fake_transcribe(audio, content_type, language=None, keyterms=None, timeout_s=300.0):
        calls["n"] += 1
        return {"text": f"transcript {calls['n']}", "provider": "deepgram",
                "detected_language": "hi", "provider_ms": 0, "audio_duration_s": 1.0}

    monkeypatch.setattr(app_mod.deepgram, "transcribe", fake_transcribe)
    c = TestClient(app_mod.app)
    c.calls, c.app_mod, c.db = calls, app_mod, db
    return c


def register(c) -> dict:
    r = c.post("/v1/installations")
    assert r.status_code == 201, r.text
    return r.json()


def transcribe(c, token=None, key=None, raw_auth=None):
    headers = {}
    if raw_auth is not None:
        headers["Authorization"] = raw_auth
    elif token:
        headers["Authorization"] = f"Bearer {token}"
    if key:
        headers["Idempotency-Key"] = key
    return c.post("/v1/transcribe", headers=headers,
                  files={"file": ("a.wav", wav(), "audio/wav")},
                  data={"provider": "deepgram", "language": "hi"})


# ---- registration ----------------------------------------------------------

def test_registration_issues_a_random_token_that_is_not_a_provider_credential(monkeypatch):
    c = make_client(monkeypatch)
    a, b = register(c), register(c)
    assert a["token"] != b["token"] and a["installation_id"] != b["installation_id"]
    assert len(a["token"]) >= 43                         # 32 random bytes, base64url
    assert a["token"] != os.environ["DEEPGRAM_API_KEY"]
    assert os.environ["DEEPGRAM_API_KEY"] not in json.dumps(a)


def test_only_a_hash_of_the_token_is_stored(monkeypatch):
    c = make_client(monkeypatch)
    token = register(c)["token"]
    with sqlite3.connect(c.db) as db:
        dump = "\n".join(db.iterdump())
    assert token not in dump
    assert client_auth.token_hash(token) in dump


def test_registration_needs_no_body_and_stores_no_address(monkeypatch):
    c = make_client(monkeypatch)
    register(c)
    with sqlite3.connect(c.db) as db:
        cols = [r[1] for r in db.execute("PRAGMA table_info(installations)")]
        dump = "\n".join(db.iterdump())
    assert cols == ["id", "token_hash", "created_at", "revoked"]
    assert "testclient" not in dump and "127.0.0.1" not in dump


def test_registration_is_limited_per_address(monkeypatch):
    c = make_client(monkeypatch, REGISTRATIONS_PER_IP_PER_HOUR=2)
    register(c); register(c)
    r = c.post("/v1/installations")
    assert r.status_code == 429
    assert r.json()["retryable"] is False and r.json()["reason"] == "registration_limited"


def test_registration_is_limited_globally(monkeypatch):
    c = make_client(monkeypatch, REGISTRATIONS_PER_HOUR=1)
    register(c)
    r = c.post("/v1/installations")
    assert r.status_code == 429 and r.json()["retryable"] is False


# ---- unauthorized requests never reach the provider ------------------------

@pytest.mark.parametrize("auth", [None, "", "Bearer", "Bearer ", "Basic abc",
                                  "Bearer not-a-real-token", "Token dg-provider-key-not-real"])
def test_unauthorized_requests_fail_without_a_provider_call(monkeypatch, auth):
    c = make_client(monkeypatch)
    register(c)
    r = transcribe(c, raw_auth=auth) if auth is not None else transcribe(c)
    assert r.status_code == 401
    assert r.json()["reason"] == "unauthorized"
    assert r.json()["retryable"] is True                 # app re-registers once
    assert c.calls["n"] == 0


def test_the_provider_key_itself_is_not_a_valid_client_token(monkeypatch):
    c = make_client(monkeypatch)
    r = transcribe(c, token=os.environ["DEEPGRAM_API_KEY"])
    assert r.status_code == 401 and c.calls["n"] == 0


def test_a_valid_token_reaches_the_provider(monkeypatch):
    c = make_client(monkeypatch)
    token = register(c)["token"]
    r = transcribe(c, token=token)
    assert r.status_code == 200 and c.calls["n"] == 1


def test_a_revoked_installation_is_refused_terminally(monkeypatch):
    c = make_client(monkeypatch)
    reg = register(c)
    c.app_mod.auth.store.revoke(reg["installation_id"])
    r = transcribe(c, token=reg["token"])
    assert r.status_code == 403
    assert r.json()["retryable"] is False and c.calls["n"] == 0


def test_rejection_happens_before_the_body_is_read():
    """The gate answers from headers alone: receive() must never be awaited."""

    class NeverAuthorized:
        rejections = {}

        def authorize(self, header, idempotency_key=None):
            raise client_auth.Rejection(401, "unauthorized", "no", True)

    downstream = {"called": False}

    async def app(scope, receive, send):
        downstream["called"] = True

    async def receive():
        raise AssertionError("request body was read")

    sent = []

    async def send(message):
        sent.append(message)

    gate = client_auth.AuthGate(app, lambda: NeverAuthorized())
    scope = {"type": "http", "path": "/v1/transcribe", "method": "POST",
             "headers": [(b"content-length", b"104857600")]}
    asyncio.run(gate(scope, receive, send))
    assert sent[0]["status"] == 401
    assert downstream["called"] is False


def test_the_gate_leaves_other_routes_alone():
    seen = []

    async def app(scope, receive, send):
        seen.append(scope["path"])

    class Boom:
        def authorize(self, header, idempotency_key=None):
            raise AssertionError("must not be consulted")

    gate = client_auth.AuthGate(app, lambda: Boom())
    for path, method in (("/healthz", "GET"), ("/v1/usage", "GET"), ("/v1/installations", "POST")):
        asyncio.run(gate({"type": "http", "path": path, "method": method, "headers": []},
                         None, None))
    assert seen == ["/healthz", "/v1/usage", "/v1/installations"]


def test_rejections_write_nothing_to_the_database(monkeypatch):
    c = make_client(monkeypatch)
    for _ in range(25):
        transcribe(c, token="garbage")
    with sqlite3.connect(c.db) as db:
        assert db.execute("SELECT COUNT(*) FROM requests").fetchone()[0] == 0


# ---- per-client limits: terminal, never retried, no provider call ----------

def test_per_minute_limit_is_terminal_and_skips_the_provider(monkeypatch):
    c = make_client(monkeypatch, CLIENT_REQUESTS_PER_MINUTE=3)
    token = register(c)["token"]
    for i in range(3):
        assert transcribe(c, token=token, key=f"job-{i}").status_code == 200
    r = transcribe(c, token=token, key="job-3")
    assert r.status_code == 429
    body = r.json()
    assert body["retryable"] is False and body["reason"] == "rate_limited"
    assert r.headers.get("retry-after") == "60"
    assert c.calls["n"] == 3


def test_per_minute_limit_is_per_installation(monkeypatch):
    c = make_client(monkeypatch, CLIENT_REQUESTS_PER_MINUTE=1)
    a, b = register(c)["token"], register(c)["token"]
    assert transcribe(c, token=a).status_code == 200
    assert transcribe(c, token=a).status_code == 429
    assert transcribe(c, token=b).status_code == 200


def test_daily_quota_is_terminal_and_skips_the_provider(monkeypatch):
    c = make_client(monkeypatch, CLIENT_TRANSCRIPTIONS_PER_DAY=2)
    token = register(c)["token"]
    assert transcribe(c, token=token, key="a").status_code == 200
    assert transcribe(c, token=token, key="b").status_code == 200
    r = transcribe(c, token=token, key="c")
    assert r.status_code == 429
    assert r.json()["retryable"] is False and r.json()["reason"] == "quota"
    assert c.calls["n"] == 2


def test_daily_quota_survives_a_restart(monkeypatch):
    c = make_client(monkeypatch, CLIENT_TRANSCRIPTIONS_PER_DAY=1)
    token = register(c)["token"]
    assert transcribe(c, token=token).status_code == 200
    importlib.reload(c.app_mod)                          # same METERING_DB
    c2 = TestClient(c.app_mod.app)
    assert transcribe(c2, token=token).status_code == 429


def test_global_daily_budget_stops_provider_calls(monkeypatch):
    c = make_client(monkeypatch, DAILY_BUDGET_INR="0.001")
    a, b = register(c)["token"], register(c)["token"]
    assert transcribe(c, token=a).status_code == 200     # spends ~Rs 0.006
    r = transcribe(c, token=b)
    assert r.status_code == 503
    assert r.json()["retryable"] is False and r.json()["reason"] == "budget"
    assert c.calls["n"] == 1


# ---- compatibility with JobRunner / idempotency ---------------------------

def test_a_replayed_retry_does_not_consume_daily_quota(monkeypatch):
    c = make_client(monkeypatch, CLIENT_TRANSCRIPTIONS_PER_DAY=1)
    token = register(c)["token"]
    first = transcribe(c, token=token, key="job-1")
    retry = transcribe(c, token=token, key="job-1")       # JobRunner retry, same key
    assert first.status_code == 200 and retry.status_code == 200
    assert retry.json()["replayed"] is True
    assert c.calls["n"] == 1


def test_a_lost_response_retry_is_served_even_at_the_per_minute_limit(monkeypatch):
    c = make_client(monkeypatch, CLIENT_REQUESTS_PER_MINUTE=1)
    token = register(c)["token"]
    assert transcribe(c, token=token, key="job-1").status_code == 200
    retry = transcribe(c, token=token, key="job-1")
    assert retry.status_code == 200 and retry.json()["replayed"] is True
    assert transcribe(c, token=token, key="job-2").status_code == 429   # new work still limited


def test_another_installations_key_does_not_bypass_quota(monkeypatch):
    c = make_client(monkeypatch, CLIENT_TRANSCRIPTIONS_PER_DAY=1)
    a, b = register(c)["token"], register(c)["token"]
    assert transcribe(c, token=a, key="k").status_code == 200
    assert transcribe(c, token=b, key="x").status_code == 200
    # b reusing a's key is NOT a replay for b, so b's exhausted quota applies.
    assert transcribe(c, token=b, key="k").status_code == 429


def test_idempotency_keys_are_scoped_to_the_installation(monkeypatch):
    c = make_client(monkeypatch)
    a, b = register(c)["token"], register(c)["token"]
    ra = transcribe(c, token=a, key="shared-key")
    rb = transcribe(c, token=b, key="shared-key")
    assert rb.json().get("replayed") is not True, "a key must not leak another install's transcript"
    assert ra.json()["text"] != rb.json()["text"]
    assert c.calls["n"] == 2


def test_limit_rejections_by_the_server_do_not_count_against_quota(monkeypatch):
    c = make_client(monkeypatch, CLIENT_TRANSCRIPTIONS_PER_DAY=1)
    token = register(c)["token"]
    empty = c.post("/v1/transcribe", headers={"Authorization": f"Bearer {token}"},
                   files={"file": ("a.wav", b"", "audio/wav")},
                   data={"provider": "deepgram"})
    assert empty.status_code == 422 and c.calls["n"] == 0
    assert transcribe(c, token=token).status_code == 200


def test_kill_switch_still_applies_to_authorized_requests(monkeypatch):
    c = make_client(monkeypatch)
    token = register(c)["token"]
    monkeypatch.setenv("TRANSCRIPTION_ENABLED", "false")
    r = transcribe(c, token=token)
    assert r.status_code == 503 and r.json()["retryable"] is False and c.calls["n"] == 0


def test_health_and_usage_report_auth_without_exposing_tokens(monkeypatch):
    c = make_client(monkeypatch, DAILY_BUDGET_INR="500")
    token = register(c)["token"]
    transcribe(c, token="garbage")
    h = c.get("/healthz").json()
    assert h["client_auth"]["required"] is True
    assert h["client_auth"]["daily_budget_inr"] == 500.0
    u = c.get("/v1/usage").json()
    assert u["auth_rejections"]["unauthorized"] == 1
    assert token not in json.dumps(h) + json.dumps(u)


def test_logs_never_contain_tokens(monkeypatch, caplog):
    import logging
    caplog.set_level(logging.INFO)
    c = make_client(monkeypatch)
    token = register(c)["token"]
    transcribe(c, token=token)
    transcribe(c, token="garbage-token-value")
    text = caplog.text
    assert token not in text and "garbage-token-value" not in text
