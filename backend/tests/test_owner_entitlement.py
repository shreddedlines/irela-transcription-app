# -*- coding: utf-8 -*-
"""
Owner entitlement (owner.py): the claim code, the owner's exemption from the
free tier, the safety limits an owner keeps, GET /v1/me, the ADMIN_TOKEN lock
on /v1/usage, the schema migration and the admin script.

No network and no credentials. Providers are scripted fakes; the recurring
assertions are provider call counts, response reasons and metering rows.
"""
import asyncio
import hashlib
import importlib
import io
import json
import logging
import os
import sqlite3
import sys
import tempfile
import threading
import time

import pytest
from fastapi.testclient import TestClient

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import client_auth                    # noqa: E402

CODE = "owner-test-code-8d1f5a2c9e7b4036"
CODE_HASH = hashlib.sha256(CODE.encode("utf-8")).hexdigest()
FREE_REASONS = {"monthly_free_allowance_exceeded", "free_daily_network_cap_exceeded",
                "free_daily_budget_exceeded"}
ALLOWANCE_KEYS = {"month", "allowance_seconds", "used_seconds", "remaining_seconds",
                  "in_progress_seconds", "resets_at"}


def wav_claiming(seconds: float, rate: int = 16000) -> bytes:
    """A RIFF/WAV header declaring [seconds] of 16-bit mono audio; tiny body."""
    n = int(round(seconds * rate)) * 2
    return (b"RIFF" + (36 + n).to_bytes(4, "little") + b"WAVEfmt " +
            (16).to_bytes(4, "little") + (1).to_bytes(2, "little") +
            (1).to_bytes(2, "little") + rate.to_bytes(4, "little") +
            (rate * 2).to_bytes(4, "little") + (2).to_bytes(2, "little") +
            (16).to_bytes(2, "little") + b"data" + n.to_bytes(4, "little") + b"\x00" * 64)


class Script:
    """A fake provider. [gate] holds a call open until set."""
    def __init__(self, name):
        self.name, self.calls, self.gate = name, 0, None

    async def __call__(self, audio, content_type, language=None, keyterms=None, timeout_s=300.0):
        self.calls += 1
        if self.gate is not None:
            while not self.gate.is_set():
                await asyncio.sleep(0.01)
        return {"text": f"{self.name} transcript", "provider": self.name,
                "detected_language": "en", "provider_ms": 0, "audio_duration_s": 1.0}


class World:
    """One reloaded app, its fake providers, and helpers to make installations."""

    def __init__(self, app_mod, dg, aai, db):
        self.app_mod, self.dg, self.aai, self.db = app_mod, dg, aai, db

    def client(self, ip: str = "198.51.100.10") -> TestClient:
        cl = TestClient(self.app_mod.app, client=(ip, 50000))
        cl.__enter__()                          # one event loop for in-flight work
        r = cl.post("/v1/installations")
        assert r.status_code == 201
        body = r.json()
        cl.headers["Authorization"] = "Bearer " + body["token"]
        cl.installation_id = body["installation_id"]
        return cl

    def owner(self, ip: str = "198.51.100.10") -> TestClient:
        cl = self.client(ip)
        r = claim(cl)
        assert r.status_code == 200, r.text
        return cl

    @property
    def calls(self) -> int:
        return self.dg.calls + self.aai.calls

    def rows(self, installation_id: str) -> list:
        with sqlite3.connect(self.db) as con:
            return con.execute(
                "SELECT plan, free_tier, network_hash, success, provider_called, est_cost_inr"
                " FROM requests WHERE installation_id = ? ORDER BY id", (installation_id,)).fetchall()


BASE_ENV = {"TRANSCRIPTION_ENABLED": "true", "DEEPGRAM_API_KEY": "dg-fake",
            "ASSEMBLYAI_API_KEY": "aai-fake", "CLIENT_REQUESTS_PER_MINUTE": "1000",
            "CLIENT_TRANSCRIPTIONS_PER_DAY": "1000", "REGISTRATIONS_PER_IP_PER_HOUR": "1000",
            "ROUTER_BACKOFF_BASE_S": "0.01", "PROVIDER_PROBE_INTERVAL_S": "0",
            "HANDLER_WAIT_S": "10"}
CLEARED = ("FREE_MONTHLY_SECONDS", "FREE_ALLOWANCE_TIMEZONE", "FREE_ALLOWANCE_UNKNOWN_FLOOR_BPS",
           "FREE_DAILY_NETWORK_SECONDS", "FREE_DAILY_BUDGET_INR", "DAILY_BUDGET_INR",
           "OWNER_CLAIM_CODE_SHA256", "OWNER_MAX_INSTALLATIONS", "OWNER_DAILY_SECONDS",
           "OWNER_MONTHLY_SECONDS", "OWNER_TRANSCRIPTIONS_PER_DAY", "OWNER_REQUESTS_PER_MINUTE",
           "ADMIN_TOKEN")


def make_world(monkeypatch, db=None, **env) -> World:
    db = db or os.path.join(tempfile.mkdtemp(), "m.sqlite3")
    monkeypatch.setenv("METERING_DB", db)
    for k, v in BASE_ENV.items():
        monkeypatch.setenv(k, v)
    for k in CLEARED:
        monkeypatch.delenv(k, raising=False)
    monkeypatch.setenv("OWNER_CLAIM_CODE_SHA256", CODE_HASH)
    for k, v in env.items():
        if v is None:
            monkeypatch.delenv(k, raising=False)
        else:
            monkeypatch.setenv(k, str(v))
    import app as app_mod
    importlib.reload(app_mod)
    dg, aai = Script("deepgram"), Script("assemblyai")
    monkeypatch.setattr(app_mod.deepgram, "transcribe", dg)
    monkeypatch.setattr(app_mod.assemblyai, "transcribe", aai)
    return World(app_mod, dg, aai, db)


@pytest.fixture()
def w(monkeypatch):
    return make_world(monkeypatch)


def claim(cl, code=CODE):
    return cl.post("/v1/owner/claim", json={"code": code})


def post(cl, seconds, key=None):
    headers = {"Idempotency-Key": key} if key else {}
    return cl.post("/v1/transcribe", headers=headers,
                   files={"file": ("clip.wav", wav_claiming(seconds), "audio/wav")},
                   data={"provider": "auto"})


def me(cl):
    r = cl.get("/v1/me")
    assert r.status_code == 200, r.text
    return r.json()


# ---- claiming -----------------------------------------------------------------

def test_claiming_is_disabled_until_the_hash_is_configured(monkeypatch):
    w = make_world(monkeypatch, OWNER_CLAIM_CODE_SHA256=None)
    cl = w.client()
    r = claim(cl)
    assert r.status_code == 404 and r.json()["reason"] == "not_found"
    assert me(cl)["plan"] == "free"


def test_a_malformed_hash_keeps_claiming_disabled(monkeypatch):
    w = make_world(monkeypatch, OWNER_CLAIM_CODE_SHA256="not-a-sha256")
    assert claim(w.client()).status_code == 404


def test_claiming_needs_an_installation_token(w):
    anonymous = TestClient(w.app_mod.app)
    assert anonymous.post("/v1/owner/claim", json={"code": CODE}).status_code == 401
    assert anonymous.post("/v1/owner/claim", json={"code": CODE},
                          headers={"Authorization": "Bearer garbage"}).status_code == 401


def test_a_wrong_code_is_refused_and_grants_nothing(w):
    cl = w.client()
    r = claim(cl, "not-the-code")
    assert r.status_code == 403 and r.json()["reason"] == "owner_claim_invalid"
    assert me(cl)["plan"] == "free"
    for bad in ("", "   ", CODE.upper(), CODE + "x"):
        assert claim(cl, bad).status_code in (403, 429)


def test_the_right_code_grants_owner_once_and_is_idempotent(w):
    cl = w.client()
    r = claim(cl, "  " + CODE + "\n")            # surrounding whitespace is ignored
    assert r.status_code == 200 and r.json() == {"plan": "owner", "status": "granted"}
    assert me(cl)["plan"] == "owner"
    assert claim(cl).json()["status"] == "already_owner"
    assert w.app_mod.auth.store.active_owner_count() == 1


def test_attempts_are_limited_before_the_code_is_checked(w):
    cl = w.client()
    for _ in range(5):
        assert claim(cl, "guess").status_code == 403
    r = claim(cl)                                   # even the right code is now refused
    assert r.status_code == 429 and r.json()["reason"] == "owner_claim_limited"
    assert me(cl)["plan"] == "free"


def test_attempts_are_limited_per_network_across_installations(w):
    for _ in range(5):
        assert claim(w.client("203.0.113.5"), "guess").status_code == 403
    assert claim(w.client("203.0.113.5")).status_code == 429     # same network, new install
    assert claim(w.client("203.0.113.6")).status_code == 200     # other network unaffected


def test_owner_installations_are_capped_and_revoking_frees_a_slot(w):
    a, b, c = w.client("192.0.2.1"), w.client("192.0.2.2"), w.client("192.0.2.3")
    assert claim(a).status_code == 200 and claim(b).status_code == 200
    r = claim(c)
    assert r.status_code == 409 and r.json()["reason"] == "owner_limit_reached"
    assert me(c)["plan"] == "free"
    w.app_mod.auth.store.revoke(a.installation_id)
    assert claim(c).status_code == 200


def test_the_owner_cap_is_configurable(monkeypatch):
    w = make_world(monkeypatch, OWNER_MAX_INSTALLATIONS=1)
    assert claim(w.client("192.0.2.1")).status_code == 200
    assert claim(w.client("192.0.2.2")).status_code == 409


def test_a_revoked_installation_cannot_claim(w):
    cl = w.client()
    w.app_mod.auth.store.revoke(cl.installation_id)
    r = claim(cl)
    assert r.status_code == 403 and r.json()["reason"] == "revoked"


def test_the_claim_body_is_bounded_and_must_be_json(w):
    cl = w.client()
    big = cl.post("/v1/owner/claim", content=b"x" * 10000,
                  headers={"Content-Type": "application/json"})
    assert big.status_code == 413
    junk = cl.post("/v1/owner/claim", content=b"not json",
                   headers={"Content-Type": "application/json"})
    assert junk.status_code == 403 and junk.json()["reason"] == "owner_claim_invalid"
    not_a_string = cl.post("/v1/owner/claim", json={"code": 12345})
    assert not_a_string.status_code == 403


def test_the_claim_code_is_never_logged_stored_or_echoed(w, caplog):
    caplog.set_level(logging.DEBUG)
    cl = w.client()
    wrong = claim(cl, CODE[:-1] + "0")
    right = claim(cl)
    assert CODE not in caplog.text and CODE[:-1] not in caplog.text
    assert CODE not in wrong.text and CODE not in right.text
    with open(w.db, "rb") as fh:
        assert CODE.encode() not in fh.read()


# ---- the owner is exempt from the free tier --------------------------------------

def test_the_owner_skips_the_monthly_free_allowance(monkeypatch):
    w = make_world(monkeypatch, FREE_MONTHLY_SECONDS=60)
    owner, free = w.owner("192.0.2.1"), w.client("192.0.2.2")
    assert post(owner, 120).status_code == 200
    r = post(free, 120)
    assert r.status_code == 429 and r.json()["reason"] == "monthly_free_allowance_exceeded"
    assert w.calls == 1


def test_the_owner_skips_the_free_network_cap_and_free_budget(monkeypatch):
    w = make_world(monkeypatch, FREE_DAILY_NETWORK_SECONDS=60, FREE_DAILY_BUDGET_INR="0.01")
    owner = w.owner("192.0.2.1")
    assert post(owner, 120).status_code == 200
    r = post(w.client("192.0.2.1"), 30)
    assert r.status_code == 429 and r.json()["reason"] in FREE_REASONS
    assert w.calls == 1


def test_owner_rows_are_metered_as_owner_not_free(w):
    owner = w.owner()
    assert post(owner, 60).status_code == 200
    plan, free_tier, network_hash, success, called, cost = w.rows(owner.installation_id)[-1]
    assert (plan, free_tier, network_hash, success, called) == ("owner", 0, None, 1, 1)
    assert cost > 0                                 # still counts toward global spend


def test_free_rows_stay_exactly_as_before(w):
    free = w.client()
    assert post(free, 60).status_code == 200
    plan, free_tier, network_hash, success, called, _ = w.rows(free.installation_id)[-1]
    assert (plan, free_tier, success, called) == ("free", 1, 1, 1)
    assert network_hash                              # free rows keep their network handle


def test_owner_usage_consumes_no_free_tier_counter(monkeypatch):
    w = make_world(monkeypatch, FREE_DAILY_NETWORK_SECONDS=120)
    owner = w.owner("192.0.2.1")
    assert post(owner, 100).status_code == 200
    # 100 owner seconds on this network would leave 20 if they counted.
    assert post(w.client("192.0.2.1"), 100).status_code == 200
    assert owner.get("/v1/allowance").json()["used_seconds"] == 0


def test_a_downgraded_owner_is_free_again_and_not_charged_for_owner_use(monkeypatch):
    w = make_world(monkeypatch, FREE_MONTHLY_SECONDS=60)
    owner = w.owner()
    assert post(owner, 100).status_code == 200
    w.app_mod.auth.store.set_plan(owner.installation_id, "free", time.time())
    assert me(owner)["plan"] == "free"
    assert owner.get("/v1/allowance").json()["used_seconds"] == 0
    assert post(owner, 50).status_code == 200        # fits the untouched 60 s
    r = post(owner, 50)
    assert r.status_code == 429 and r.json()["reason"] == "monthly_free_allowance_exceeded"


# ---- the owner keeps the safety limits ----------------------------------------------

def test_the_owner_daily_ceiling_refuses_before_any_provider_call(monkeypatch):
    w = make_world(monkeypatch, OWNER_DAILY_SECONDS=100)
    owner = w.owner()
    assert post(owner, 60).status_code == 200
    r = post(owner, 60)
    assert r.status_code == 429 and r.json()["reason"] == "owner_daily_limit_exceeded"
    assert r.json()["retryable"] is False
    assert w.calls == 1


def test_the_owner_monthly_ceiling_refuses_before_any_provider_call(monkeypatch):
    w = make_world(monkeypatch, OWNER_DAILY_SECONDS=10000, OWNER_MONTHLY_SECONDS=100)
    owner = w.owner()
    assert post(owner, 60).status_code == 200
    r = post(owner, 60)
    assert r.status_code == 429 and r.json()["reason"] == "owner_monthly_limit_exceeded"
    assert w.calls == 1


def test_the_default_ceilings_are_four_hours_a_day_and_thirty_a_month(w):
    assert w.app_mod.owner_guard.daily_seconds == 4 * 3600
    assert w.app_mod.owner_guard.monthly_seconds == 30 * 3600
    limits = w.app_mod.auth.limits
    assert limits.owner_transcriptions_per_day == 150 and limits.owner_requests_per_minute == 6


@pytest.mark.parametrize("value", ["0", "-5", "garbage"])
def test_a_ceiling_cannot_be_switched_off_by_a_typo(monkeypatch, value):
    w = make_world(monkeypatch, OWNER_DAILY_SECONDS=value, OWNER_MONTHLY_SECONDS=value,
                   OWNER_MAX_INSTALLATIONS=value)
    assert w.app_mod.owner_guard.daily_seconds == 4 * 3600
    assert w.app_mod.owner_guard.monthly_seconds == 30 * 3600
    assert w.app_mod.owner_claim.max_installations == 2


def test_the_owner_ceiling_holds_for_concurrent_jobs(monkeypatch):
    w = make_world(monkeypatch, OWNER_DAILY_SECONDS=100, HANDLER_WAIT_S="0.3")
    owner = w.owner()
    w.dg.gate = threading.Event()                   # first job held open, reserved
    first = post(owner, 60, key="job-a")
    assert first.status_code == 503 and first.json()["reason"] == "processing"
    second = post(owner, 60, key="job-b")           # 60 + 60 reserved would exceed 100
    assert second.status_code == 429 and second.json()["reason"] == "owner_daily_limit_exceeded"
    w.dg.gate.set()
    for _ in range(100):
        done = post(owner, 60, key="job-a")
        if done.status_code == 200:
            break
        time.sleep(0.05)
    assert done.status_code == 200 and w.calls == 1
    # The reservation is released once the job is metered: 40 s remain.
    assert post(owner, 40).status_code == 200


def test_an_owner_retry_is_not_charged_twice(monkeypatch):
    w = make_world(monkeypatch, OWNER_DAILY_SECONDS=100)
    owner = w.owner()
    assert post(owner, 60, key="same-job").status_code == 200
    replay = post(owner, 60, key="same-job")
    assert replay.status_code == 200 and replay.json()["replayed"] is True
    assert w.calls == 1
    assert w.app_mod.owner_guard.usage(owner.installation_id)["day_seconds"] == 60


def test_the_owner_has_its_own_daily_count_and_free_users_keep_theirs(monkeypatch):
    w = make_world(monkeypatch, OWNER_TRANSCRIPTIONS_PER_DAY=2, CLIENT_TRANSCRIPTIONS_PER_DAY=1)
    owner, free = w.owner("192.0.2.1"), w.client("192.0.2.2")
    assert post(owner, 5).status_code == 200 and post(owner, 5).status_code == 200
    r = post(owner, 5)
    assert r.status_code == 429 and r.json()["reason"] == "quota"
    assert post(free, 5).status_code == 200
    assert post(free, 5).json()["reason"] == "quota"


def test_the_owner_per_minute_limit_applies(monkeypatch):
    w = make_world(monkeypatch, OWNER_REQUESTS_PER_MINUTE=1)
    owner = w.owner()
    assert post(owner, 5).status_code == 200
    r = post(owner, 5)
    assert r.status_code == 429 and r.json()["reason"] == "rate_limited"


def test_the_global_budget_still_stops_the_owner(monkeypatch):
    w = make_world(monkeypatch, DAILY_BUDGET_INR="0.05")
    owner = w.owner()
    assert post(owner, 60).status_code == 200       # ~Rs 0.38 of spend now recorded
    r = post(owner, 60)
    assert r.status_code == 503 and r.json()["reason"] == "budget"
    assert w.calls == 1


def test_the_kill_switch_still_stops_the_owner(w, monkeypatch):
    owner = w.owner()
    monkeypatch.setenv("TRANSCRIPTION_ENABLED", "false")
    r = post(owner, 5)
    assert r.status_code == 503 and r.json()["reason"] == "disabled"
    assert w.calls == 0


def test_the_per_file_limit_still_applies_to_the_owner(w):
    owner = w.owner()
    r = post(owner, 3700)                           # over the 3600 s per-file limit
    assert r.status_code == 413
    assert w.calls == 0


def test_a_revoked_owner_is_refused(w):
    owner = w.owner()
    w.app_mod.auth.store.revoke(owner.installation_id)
    r = post(owner, 5)
    assert r.status_code == 403 and r.json()["reason"] == "revoked"
    assert w.calls == 0


# ---- GET /v1/me ------------------------------------------------------------------

def test_me_needs_a_token(w):
    assert TestClient(w.app_mod.app).get("/v1/me").status_code == 401


def test_me_for_a_free_installation(w):
    free = w.client()
    body = me(free)
    assert set(body) == {"plan", "unlimited", "monthly", "daily", "limits", "service"}
    assert body["plan"] == "free" and body["unlimited"] is False
    assert body["monthly"] == free.get("/v1/allowance").json()
    assert body["daily"] == {"transcriptions_used": 0,
                             "transcriptions_limit": w.app_mod.auth.limits.transcriptions_per_day,
                             "window": "rolling_24h"}
    assert body["limits"]["max_duration_seconds"] == 3600
    assert body["service"] == {"available": True}
    assert post(free, 30).status_code == 200
    after = me(free)
    assert after["daily"]["transcriptions_used"] == 1 and after["monthly"]["used_seconds"] == 30


def test_me_for_an_owner_says_unlimited_and_reveals_no_ceiling(monkeypatch):
    w = make_world(monkeypatch, OWNER_DAILY_SECONDS=12345, OWNER_MONTHLY_SECONDS=67890,
                   OWNER_TRANSCRIPTIONS_PER_DAY=137, OWNER_REQUESTS_PER_MINUTE=7)
    owner = w.owner()
    r = owner.get("/v1/me")
    body = r.json()
    assert body["plan"] == "owner" and body["unlimited"] is True
    assert body["monthly"] is None and body["daily"] is None
    assert "requests_per_minute" not in body["limits"]
    for secret in ("12345", "67890", "137", "owner_daily", "owner_monthly"):
        assert secret not in r.text


def test_me_reports_service_availability(monkeypatch):
    w = make_world(monkeypatch, DAILY_BUDGET_INR="0.05")
    free = w.client()
    assert me(free)["service"]["available"] is True
    assert post(free, 60).status_code == 200        # global budget now spent
    assert me(free)["service"]["available"] is False
    monkeypatch.delenv("DAILY_BUDGET_INR")
    w2 = make_world(monkeypatch, TRANSCRIPTION_ENABLED="false")
    assert me(w2.client())["service"]["available"] is False


def test_me_and_claim_never_call_a_provider(w):
    cl = w.client()
    me(cl), claim(cl), me(cl)
    assert w.calls == 0


def test_the_allowance_endpoint_is_unchanged_for_free_and_owner(w):
    free, owner = w.client("192.0.2.1"), w.owner("192.0.2.2")
    for cl in (free, owner):
        r = cl.get("/v1/allowance")
        assert r.status_code == 200 and set(r.json()) == ALLOWANCE_KEYS


# ---- /v1/usage behind ADMIN_TOKEN ----------------------------------------------------

def test_usage_stays_open_when_no_admin_token_is_set(w):
    assert TestClient(w.app_mod.app).get("/v1/usage").status_code == 200


def test_usage_requires_the_admin_token_once_it_is_set(monkeypatch):
    admin = "admin-token-for-tests-3c9e1a"
    w = make_world(monkeypatch, ADMIN_TOKEN=admin)
    anon = TestClient(w.app_mod.app)
    cl = w.client()
    for headers in ({}, {"Authorization": "Bearer wrong"}, {"Authorization": admin},
                    {"Authorization": cl.headers["Authorization"]}):
        r = anon.get("/v1/usage", headers=headers)
        assert r.status_code == 401 and r.json()["reason"] == "admin_unauthorized"
        assert admin not in r.text
    ok = anon.get("/v1/usage", headers={"Authorization": "Bearer " + admin})
    assert ok.status_code == 200 and "est_cost_inr" in ok.json()


def test_usage_reports_owner_activity(w):
    w.owner()
    body = TestClient(w.app_mod.app).get("/v1/usage").json()
    assert body["owner"]["claim_enabled"] is True
    assert body["owner"]["active_installations"] == 1
    assert body["owner"]["claims"]["granted"] == 1
    assert body["free_tier"] and body["auth_rejections"] is not None    # existing fields intact


# ---- migration of an existing database -----------------------------------------------

def test_an_existing_database_migrates_without_changing_installations(monkeypatch):
    db = os.path.join(tempfile.mkdtemp(), "old.sqlite3")
    with sqlite3.connect(db) as con:                  # the pre-owner schema, with data
        con.executescript("""
            CREATE TABLE requests (id INTEGER PRIMARY KEY AUTOINCREMENT, ts REAL NOT NULL,
                provider TEXT NOT NULL, bytes INTEGER NOT NULL, duration_s REAL NOT NULL,
                success INTEGER NOT NULL, status INTEGER NOT NULL,
                replayed INTEGER NOT NULL DEFAULT 0, est_cost_inr REAL NOT NULL DEFAULT 0,
                chars INTEGER NOT NULL DEFAULT 0, idempotency_key TEXT);
            CREATE TABLE installations (id TEXT PRIMARY KEY, token_hash TEXT NOT NULL UNIQUE,
                created_at REAL NOT NULL, revoked INTEGER NOT NULL DEFAULT 0);
        """)
        con.execute("INSERT INTO installations VALUES (?,?,?,0)",
                    ("legacy-id", client_auth.token_hash("legacy-token"), time.time()))
        con.execute("INSERT INTO requests (ts, provider, bytes, duration_s, success, status)"
                    " VALUES (?,?,?,?,?,?)", (time.time(), "deepgram", 10, 5.0, 1, 200))
    w = make_world(monkeypatch, db=db)
    with sqlite3.connect(db) as con:
        cols = [r[1] for r in con.execute("PRAGMA table_info(installations)")]
        assert cols == ["id", "token_hash", "created_at", "revoked"]
        assert con.execute("SELECT plan FROM requests").fetchall() == [("free",)]
        assert con.execute("SELECT COUNT(*) FROM installation_plans").fetchone()[0] == 0
    legacy = TestClient(w.app_mod.app, headers={"Authorization": "Bearer legacy-token"})
    assert legacy.get("/v1/me").json()["plan"] == "free"
    assert legacy.post("/v1/owner/claim", json={"code": CODE}).status_code == 200
    assert legacy.get("/v1/me").json()["plan"] == "owner"


# ---- owner_admin.py ---------------------------------------------------------------

def test_owner_admin_lists_downgrades_and_revokes(w, capsys):
    import owner_admin
    owner = w.owner()
    token = owner.headers["Authorization"].split()[1]
    assert owner_admin.main(["--db", w.db, "list"]) == 0
    out = capsys.readouterr().out
    assert owner.installation_id in out and "active" in out
    assert token not in out and client_auth.token_hash(token) not in out
    assert owner_admin.main(["--db", w.db, "downgrade", owner.installation_id]) == 0
    assert me(owner)["plan"] == "free"
    assert owner_admin.main(["--db", w.db, "revoke", owner.installation_id]) == 0
    assert owner.get("/v1/me").status_code == 403
    assert owner_admin.main(["--db", w.db, "revoke", "no-such-id"]) == 1


def test_owner_admin_hashes_a_code_from_stdin(monkeypatch, capsys):
    import owner_admin
    monkeypatch.setattr(sys, "stdin", io.StringIO(CODE + "\n"))
    assert owner_admin.main(["hash"]) == 0
    assert capsys.readouterr().out.strip() == CODE_HASH
