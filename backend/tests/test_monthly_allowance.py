# -*- coding: utf-8 -*-
"""
Free monthly allowance: 20 minutes of audio per installation per calendar
month, enforced before any provider call.

No network and no credentials. Both providers are scripted fakes; the recurring
assertions are provider call counts and the seconds the metering database says
were used.
"""
import asyncio
import datetime as dt
import importlib
import os
import sqlite3
import sys
import tempfile
import threading
import time

import pytest
from fastapi.testclient import TestClient

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import allowance as allowance_mod     # noqa: E402
from providers import errors          # noqa: E402

REASON = "monthly_free_allowance_exceeded"


def wav_claiming(seconds: float, rate: int = 16000) -> bytes:
    """A RIFF/WAV whose header declares [seconds] of 16-bit mono audio. The
    body is a few bytes: the probe reads the header, the fake provider ignores
    the audio, and a '20-minute' upload stays tiny."""
    n = int(round(seconds * rate)) * 2
    return (b"RIFF" + (36 + n).to_bytes(4, "little") + b"WAVEfmt " +
            (16).to_bytes(4, "little") + (1).to_bytes(2, "little") +
            (1).to_bytes(2, "little") + rate.to_bytes(4, "little") +
            (rate * 2).to_bytes(4, "little") + (2).to_bytes(2, "little") +
            (16).to_bytes(2, "little") + b"data" + n.to_bytes(4, "little") + b"\x00" * 64)


class Script:
    """A fake provider: pops one behaviour per call; the last one repeats."""
    def __init__(self, name):
        self.name = name
        self.behaviours = ["ok"]
        self.calls = 0
        self.gate = None
        self.measured = 1.0

    async def __call__(self, audio, content_type, language=None, keyterms=None, timeout_s=300.0):
        self.calls += 1
        b = self.behaviours.pop(0) if len(self.behaviours) > 1 else self.behaviours[0]
        if self.gate is not None:
            while not self.gate.is_set():
                await asyncio.sleep(0.01)
        if b == "ok":
            return {"text": f"{self.name} transcript", "provider": self.name,
                    "detected_language": "hi", "provider_ms": 0,
                    "audio_duration_s": self.measured}
        raise b


def err(kind, status=502):
    return errors.ProviderError(status, f"fake {kind}", kind=kind)


@pytest.fixture()
def c(monkeypatch):
    db = os.path.join(tempfile.mkdtemp(), "m.sqlite3")
    for k, v in {"METERING_DB": db, "TRANSCRIPTION_ENABLED": "true",
                 "DEEPGRAM_API_KEY": "dg-fake", "ASSEMBLYAI_API_KEY": "aai-fake",
                 "CLIENT_REQUESTS_PER_MINUTE": "1000", "CLIENT_TRANSCRIPTIONS_PER_DAY": "1000",
                 "ROUTER_BACKOFF_BASE_S": "0.01", "PROVIDER_PROBE_INTERVAL_S": "0",
                 "HANDLER_WAIT_S": "10"}.items():
        monkeypatch.setenv(k, v)
    for k in ("FREE_MONTHLY_SECONDS", "FREE_ALLOWANCE_TIMEZONE", "FREE_ALLOWANCE_UNKNOWN_FLOOR_BPS",
              "DAILY_BUDGET_INR"):
        monkeypatch.delenv(k, raising=False)
    import app as app_mod
    importlib.reload(app_mod)
    dg, aai = Script("deepgram"), Script("assemblyai")
    monkeypatch.setattr(app_mod.deepgram, "transcribe", dg)
    monkeypatch.setattr(app_mod.assemblyai, "transcribe", aai)
    client = TestClient(app_mod.app)
    client.__enter__()                         # one event loop for in-flight work
    client.headers["Authorization"] = "Bearer " + client.post("/v1/installations").json()["token"]
    client.dg, client.aai, client.app_mod, client.db = dg, aai, app_mod, db
    return client


def post(c, seconds, key=None, audio=None, headers=None):
    h = dict(headers or {})
    if key:
        h["Idempotency-Key"] = key
    return c.post("/v1/transcribe", headers=h,
                  files={"file": ("clip.wav", audio if audio is not None else wav_claiming(seconds), "audio/wav")},
                  data={"provider": "auto"})


def usage(c):
    r = c.get("/v1/allowance")
    assert r.status_code == 200
    return r.json()


def calls(c):
    return c.dg.calls + c.aai.calls


def rows(c, where="1=1"):
    with sqlite3.connect(c.db) as con:
        return con.execute(f"SELECT provider, success, replayed, provider_called, attempts, failed_over,"
                           f" duration_s, allowance_s, status FROM requests WHERE {where} ORDER BY id").fetchall()


# ---- 1. first job within the allowance -----------------------------------
def test_first_job_within_allowance_succeeds(c):
    assert usage(c)["used_seconds"] == 0 and usage(c)["allowance_seconds"] == 1200
    r = post(c, 300)
    assert r.status_code == 200 and r.json()["provider"] == "deepgram"
    u = usage(c)
    assert (u["used_seconds"], u["remaining_seconds"]) == (300, 900)
    assert calls(c) == 1


# ---- 2. cumulative jobs consume the allowance --------------------------------
def test_cumulative_jobs_consume_allowance(c):
    for expected_used in (400, 800, 1200):
        assert post(c, 400).status_code == 200
        assert usage(c)["used_seconds"] == expected_used
    assert usage(c)["remaining_seconds"] == 0
    r = post(c, 1)
    assert r.status_code == 429 and r.json()["reason"] == REASON
    assert calls(c) == 3


# ---- 3. the exact 20-minute boundary --------------------------------------------
def test_exact_twenty_minute_boundary_succeeds(c):
    assert post(c, 1200).status_code == 200            # one job of exactly 20:00
    assert usage(c)["remaining_seconds"] == 0
    assert post(c, 0.5).status_code == 429


def test_exact_boundary_across_jobs(c):
    assert post(c, 600).status_code == 200
    assert post(c, 600).status_code == 200              # 600 + 600 == 1200: allowed
    assert post(c, 0.1).status_code == 429


# ---- 4 & 5. a job that would exceed is refused before any provider ---------------
def test_job_exceeding_remaining_is_rejected_before_provider_call(c):
    assert post(c, 1000).status_code == 200
    before = calls(c)
    r = post(c, 300)                                    # 1000 + 300 > 1200
    assert r.status_code == 429
    body = r.json()
    assert body["reason"] == REASON and body["retryable"] is False
    a = body["allowance"]
    assert a["used_seconds"] == 1000 and a["remaining_seconds"] == 200 and a["requested_seconds"] == 300
    assert a["month"] == usage(c)["month"] and a["allowance_seconds"] == 1200
    assert "remaining free minutes" in body["error"]
    assert calls(c) == before, "never partially processed: no provider was contacted"
    assert usage(c)["used_seconds"] == 1000


def test_rejected_job_creates_no_provider_call_and_no_usage(c):
    assert post(c, 1200).status_code == 200
    r = post(c, 60)
    assert r.status_code == 429 and "used your 20 free minutes" in r.json()["error"]
    assert calls(c) == 1
    last = rows(c)[-1]
    assert last[1] == 0 and last[3] == 0 and last[8] == 429     # not successful, no provider, 429
    # A refusal does not consume the separate 40/day provider-call quota either.
    assert c.app_mod.meter.provider_calls_since(
        c.app_mod.auth.store.lookup(c.headers["Authorization"].split()[1])[0], 0) == 1


# ---- 6. failures never consume ----------------------------------------------------
def test_failed_provider_attempts_do_not_consume_unless_ultimately_successful(c):
    c.dg.behaviours = [err(errors.CREDENTIAL, 401)]
    c.aai.behaviours = [err(errors.CREDENTIAL, 401)]
    r = post(c, 300)
    assert r.status_code == 503 and r.json()["reason"] == "providers_unavailable"
    assert usage(c)["used_seconds"] == 0
    c.aai.behaviours = [err(errors.INPUT, 400)]
    c.app_mod.health.record_success("deepgram")
    c.dg.behaviours = [err(errors.INPUT, 400)]
    assert post(c, 300).status_code in (400, 413, 415, 422)
    assert usage(c)["used_seconds"] == 0
    c.dg.behaviours = ["ok"]
    assert post(c, 300).status_code == 200
    assert usage(c)["used_seconds"] == 300


# ---- 7. failover counts once ----------------------------------------------------------
def test_failover_counts_once(c):
    c.dg.behaviours = [err(errors.TRANSIENT), err(errors.TRANSIENT)]
    r = post(c, 300, key="job-failover")
    assert r.status_code == 200 and r.json()["provider"] == "assemblyai"
    assert c.dg.calls == 2 and c.aai.calls == 1
    counted = rows(c, "success = 1 AND replayed = 0 AND provider_called = 1")
    assert len(counted) == 1 and counted[0][4] == 3 and counted[0][5] == 1   # 3 attempts, failed over
    assert usage(c)["used_seconds"] == 300


# ---- 8. idempotency --------------------------------------------------------------------
def test_idempotent_replay_does_not_double_count(c):
    assert post(c, 300, key="job-1").status_code == 200
    r = post(c, 300, key="job-1")
    assert r.status_code == 200 and r.json()["replayed"] is True
    assert c.get("/v1/transcribe/status", headers={"Idempotency-Key": "job-1"}).json()["replayed"] is True
    assert calls(c) == 1 and usage(c)["used_seconds"] == 300


def test_retry_after_cache_loss_is_not_charged_again_even_near_the_limit(c, monkeypatch):
    monkeypatch.setenv("FREE_MONTHLY_SECONDS", "400")
    assert post(c, 300, key="job-restart").status_code == 200
    c.app_mod.idempotency._data.clear() if hasattr(c.app_mod.idempotency, "_data") else \
        c.app_mod.idempotency.__init__()                  # simulate a restart losing the cache
    # 300 more seconds would not fit into the 100 left, but it is the same job.
    r = post(c, 300, key="job-restart")
    assert r.status_code == 200
    assert usage(c)["used_seconds"] == 300


def test_joining_a_running_job_does_not_reserve_or_count_twice(c, monkeypatch):
    monkeypatch.setenv("HANDLER_WAIT_S", "0.3")
    c.dg.gate = threading.Event()                       # provider call held open
    r1 = post(c, 700, key="job-join")
    assert r1.status_code == 503 and r1.json()["reason"] == "processing"
    assert usage(c)["in_progress_seconds"] == 700       # reserved while it runs
    # A different job that does not fit next to the running one is refused...
    r = post(c, 600)
    assert r.status_code == 429 and r.json()["reason"] == REASON
    # ...while a retry of the running job joins it without a second reservation.
    assert post(c, 700, key="job-join").json()["reason"] == "processing"
    assert usage(c)["in_progress_seconds"] == 700
    c.dg.gate.set()
    for _ in range(100):
        done = post(c, 700, key="job-join")
        if done.status_code == 200:
            break
        time.sleep(0.05)
    assert done.status_code == 200 and c.dg.calls == 1
    u = usage(c)
    assert u["used_seconds"] == 700 and u["in_progress_seconds"] == 0 and u["remaining_seconds"] == 500


# ---- 9. calendar month ---------------------------------------------------------------------
def test_new_calendar_month_resets_allowance(c):
    assert post(c, 1200).status_code == 200
    assert post(c, 10).status_code == 429
    month, start, end = allowance_mod.month_window(time.time(), dt.timezone.utc)
    c.app_mod.allowance._clock = lambda: end + 1       # the first second of next month
    u = usage(c)
    assert u["used_seconds"] == 0 and u["remaining_seconds"] == 1200 and u["month"] != month
    assert post(c, 10).status_code == 200


def test_month_window_boundaries():
    utc = dt.timezone.utc
    t = dt.datetime(2026, 12, 31, 23, 59, 59, tzinfo=utc).timestamp()
    m, s, e = allowance_mod.month_window(t, utc)
    assert m == "2026-12"
    assert e == dt.datetime(2027, 1, 1, tzinfo=utc).timestamp()
    assert allowance_mod.month_window(e, utc)[0] == "2027-01"
    assert allowance_mod.month_window(dt.datetime(2026, 2, 28, 12, tzinfo=utc).timestamp(), utc)[0] == "2026-02"


def test_month_follows_the_configured_timezone(c, monkeypatch):
    try:
        from zoneinfo import ZoneInfo
        ZoneInfo("Asia/Kolkata")
    except Exception:
        pytest.skip("tz database not available")
    monkeypatch.setenv("FREE_ALLOWANCE_TIMEZONE", "Asia/Kolkata")
    # 2026-09-30 20:00 UTC is already 1 October in India.
    t = dt.datetime(2026, 9, 30, 20, 0, tzinfo=dt.timezone.utc).timestamp()
    c.app_mod.allowance._clock = lambda: t
    assert usage(c)["month"] == "2026-10"


# ---- 10. scope: only cloud transcription is metered against the allowance ------------------
def test_allowance_applies_only_to_cloud_transcription(c):
    """
    LOCAL transcription runs on the phone and never contacts this backend, so
    it cannot touch the allowance. On the backend the allowance gates exactly one
    route; everything else keeps working for an installation that used it all.
    """
    assert post(c, 1200).status_code == 200
    assert post(c, 1).status_code == 429
    assert c.get("/healthz").status_code == 200
    assert c.get("/v1/transcribe/status", headers={"Idempotency-Key": "nope"}).status_code == 404
    assert c.get("/v1/allowance").status_code == 200
    assert TestClient(c.app_mod.app).post("/v1/installations").status_code == 201
    import inspect
    src = inspect.getsource(c.app_mod)
    assert src.count("allowance.admit(") == 1
    assert "allowance.admit(" in inspect.getsource(c.app_mod.transcribe)


def test_other_installations_are_independent(c):
    assert post(c, 1200).status_code == 200
    other = TestClient(c.app_mod.app)
    other.headers["Authorization"] = "Bearer " + other.post("/v1/installations").json()["token"]
    r = other.post("/v1/transcribe", files={"file": ("a.wav", wav_claiming(300), "audio/wav")},
                   data={"provider": "auto"})
    assert r.status_code == 200
    assert other.get("/v1/allowance").json()["used_seconds"] == 300


# ---- unprobeable audio ---------------------------------------------------------------------------
def test_unprobeable_audio_is_sized_conservatively_from_bytes(c):
    # 3 MB of an unknown container at the 16 kbps floor bounds to 1500 s: refused up front.
    big_unknown = b"ID3" + b"\x01" * (3 * 1024 * 1024)
    r = post(c, 0, audio=big_unknown)
    assert r.status_code == 429 and calls(c) == 0
    assert r.json()["allowance"]["requested_seconds"] == 1200 * 0 + round(len(big_unknown) * 8 / 16000, 2)
    # A small unknown file fits and is charged the provider-measured duration.
    c.dg.measured = 42.0
    small_unknown = b"ID3" + b"\x01" * (100 * 1024)
    assert post(c, 0, audio=small_unknown).status_code == 200
    assert usage(c)["used_seconds"] == 42.0


# ---- endpoint -----------------------------------------------------------------------------------------
def test_allowance_endpoint_requires_a_token_and_reports_the_month(c):
    assert TestClient(c.app_mod.app).get("/v1/allowance").status_code == 401
    u = usage(c)
    assert set(u) == {"month", "allowance_seconds", "used_seconds", "remaining_seconds",
                      "in_progress_seconds", "resets_at"}
    assert u["month"] == dt.datetime.now(dt.timezone.utc).strftime("%Y-%m")
    assert u["resets_at"].startswith(allowance_mod.month_window(time.time(), dt.timezone.utc)[0][:4])


def test_allowance_is_configurable(c, monkeypatch):
    monkeypatch.setenv("FREE_MONTHLY_SECONDS", "60")
    assert usage(c)["allowance_seconds"] == 60
    assert post(c, 61).status_code == 429
    assert post(c, 60).status_code == 200


# ---- existing safeguards still apply ---------------------------------------------------------------
def test_daily_quota_and_budget_still_apply_alongside(c, monkeypatch):
    monkeypatch.setenv("CLIENT_TRANSCRIPTIONS_PER_DAY", "2")
    importlib.reload(c.app_mod)
    c2 = TestClient(c.app_mod.app)
    c2.__enter__()
    monkeypatch.setattr(c.app_mod.deepgram, "transcribe", c.dg)
    c2.headers["Authorization"] = "Bearer " + c2.post("/v1/installations").json()["token"]
    assert post(c2, 10).status_code == 200 and post(c2, 10).status_code == 200
    r = post(c2, 10)
    assert r.status_code == 429 and r.json()["reason"] == "quota"      # the daily limit, not the allowance

    monkeypatch.setenv("CLIENT_TRANSCRIPTIONS_PER_DAY", "1000")
    monkeypatch.setenv("DAILY_BUDGET_INR", "0.01")         # already exceeded by the two jobs above
    importlib.reload(c.app_mod)
    c3 = TestClient(c.app_mod.app)
    c3.__enter__()
    monkeypatch.setattr(c.app_mod.deepgram, "transcribe", c.dg)
    c3.headers["Authorization"] = "Bearer " + c3.post("/v1/installations").json()["token"]
    r = post(c3, 10)
    assert r.status_code == 503 and r.json()["reason"] == "budget"


def test_backward_compatible_refusal_shape_for_the_current_android_client(c):
    """The shipped client treats retryable=false as terminal and shows `error`."""
    assert post(c, 1200).status_code == 200
    r = post(c, 30)
    body = r.json()
    assert r.status_code == 429 and body["retryable"] is False
    assert isinstance(body["error"], str) and body["error"]
    assert "retry-after" not in {k.lower() for k in r.headers}   # nothing invites a retry
