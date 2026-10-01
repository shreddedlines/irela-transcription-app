# -*- coding: utf-8 -*-
"""
Free-tier abuse protection: a daily cap on free minutes per client network and
a daily free-spend ceiling, both refusing before any provider call.

These exist because an installation is free to create: clearing app data or
reinstalling earns another monthly allowance. No network and no credentials
here; the providers are scripted fakes and the recurring assertion is that they
were not called.
"""
import datetime as dt
import importlib
import os
import sqlite3
import sys
import tempfile
import time

import pytest
from fastapi.testclient import TestClient

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import free_tier as ft_mod            # noqa: E402
from providers import errors          # noqa: E402

from test_monthly_allowance import Script, wav_claiming, err   # noqa: E402

NETWORK_CAP = ft_mod.NETWORK_CAP_REASON
FREE_BUDGET = ft_mod.BUDGET_REASON


@pytest.fixture()
def app_mod(monkeypatch):
    db = os.path.join(tempfile.mkdtemp(), "m.sqlite3")
    for k, v in {"METERING_DB": db, "TRANSCRIPTION_ENABLED": "true",
                 "DEEPGRAM_API_KEY": "dg-fake", "ASSEMBLYAI_API_KEY": "aai-fake",
                 "CLIENT_REQUESTS_PER_MINUTE": "1000", "CLIENT_TRANSCRIPTIONS_PER_DAY": "1000",
                 "REGISTRATIONS_PER_IP_PER_HOUR": "1000", "REGISTRATIONS_PER_HOUR": "10000",
                 "ROUTER_BACKOFF_BASE_S": "0.01", "PROVIDER_PROBE_INTERVAL_S": "0",
                 "HANDLER_WAIT_S": "10"}.items():
        monkeypatch.setenv(k, v)
    for k in ("FREE_MONTHLY_SECONDS", "FREE_DAILY_NETWORK_SECONDS", "FREE_DAILY_BUDGET_INR",
              "FREE_ALLOWANCE_TIMEZONE", "DAILY_BUDGET_INR"):
        monkeypatch.delenv(k, raising=False)
    import app as app_mod
    importlib.reload(app_mod)
    dg, aai = Script("deepgram"), Script("assemblyai")
    monkeypatch.setattr(app_mod.deepgram, "transcribe", dg)
    monkeypatch.setattr(app_mod.assemblyai, "transcribe", aai)
    app_mod.dg, app_mod.aai, app_mod.db = dg, aai, db
    return app_mod


def device(app_mod, address="203.0.113.7"):
    """A fresh installation (as a reinstall or 'clear app data' produces) whose
    requests appear to come from [address]."""
    c = TestClient(app_mod.app, client=(address, 51234))
    c.__enter__()
    c.headers["Authorization"] = "Bearer " + c.post("/v1/installations").json()["token"]
    return c


def post(c, seconds, key=None):
    h = {"Idempotency-Key": key} if key else {}
    return c.post("/v1/transcribe", headers=h,
                  files={"file": ("clip.wav", wav_claiming(seconds), "audio/wav")},
                  data={"provider": "auto"})


def calls(app_mod):
    return app_mod.dg.calls + app_mod.aai.calls


def rows(app_mod, where="1=1"):
    with sqlite3.connect(app_mod.db) as con:
        return con.execute("SELECT free_tier, network_hash, success, est_cost_inr, allowance_s"
                           f" FROM requests WHERE {where} ORDER BY id").fetchall()


# ---- repeated registration from one address --------------------------------
def test_repeated_registration_from_one_address_stops_at_the_daily_network_cap(app_mod):
    """Three 'reinstalls' each get a fresh 20-minute allowance, but the network
    cap stops the fourth: 60 free minutes per address per day."""
    for attempt in range(3):
        c = device(app_mod)
        assert post(c, 1200).status_code == 200, f"reinstall {attempt + 1} of 3"
    assert calls(app_mod) == 3

    fresh = device(app_mod)                       # a fourth 'clear app data'
    assert fresh.get("/v1/allowance").json()["remaining_seconds"] == 1200
    r = post(fresh, 600)
    assert r.status_code == 429
    assert r.json()["reason"] == NETWORK_CAP and r.json()["retryable"] is False
    assert r.json()["free_tier"]["used_seconds_today"] == 3600
    assert calls(app_mod) == 3, "no provider call for a refused job"


def test_the_cap_counts_minutes_not_installations(app_mod):
    c = device(app_mod)
    assert post(c, 1200).status_code == 200
    second = device(app_mod)
    assert post(second, 1200).status_code == 200
    third = device(app_mod)
    assert post(third, 1200).status_code == 200            # exactly 3600 s: allowed
    fourth = device(app_mod)
    assert post(fourth, 1).status_code == 429              # one second more is not
    assert calls(app_mod) == 3


def test_a_job_larger_than_what_is_left_for_the_network_is_refused_whole(app_mod):
    c = device(app_mod)
    assert post(c, 1200).status_code == 200
    c2 = device(app_mod)
    assert post(c2, 1200).status_code == 200                # 2400 used, 1200 left today
    c3 = device(app_mod)
    assert post(c3, 1200).status_code == 200                # exactly what is left: allowed
    c4 = device(app_mod)
    assert post(c4, 60).status_code == 429
    assert calls(app_mod) == 3


# ---- separate addresses are independent -------------------------------------
def test_separate_addresses_are_independent(app_mod):
    for i in range(3):
        assert post(device(app_mod, "198.51.100.5"), 1200).status_code == 200
    assert post(device(app_mod, "198.51.100.5"), 60).status_code == 429
    other = device(app_mod, "198.51.100.6")                # a different network
    assert post(other, 1200).status_code == 200
    assert calls(app_mod) == 4


def test_a_request_without_a_usable_address_is_not_capped_as_one_network(app_mod):
    # No client address at all (an odd proxy, a direct socket): there is nothing
    # to cap by, so the job is left to the monthly allowance and no handle is
    # stored. Any real address, including a test one, is hashed.
    assert app_mod.free_tier.network_hash(None) is None
    assert app_mod.free_tier.network_hash("   ") is None
    assert app_mod.free_tier.network_hash("unknown") is None
    c = TestClient(app_mod.app)                     # supplies its own default address
    c.__enter__()
    c.headers["Authorization"] = "Bearer " + c.post("/v1/installations").json()["token"]
    assert post(c, 1200).status_code == 200
    stored = rows(app_mod, "success = 1")[0][1]
    assert stored is not None and len(stored) == 32


# ---- day and month boundaries -------------------------------------------------
def test_the_network_cap_resets_the_next_day(app_mod):
    for _ in range(3):
        assert post(device(app_mod), 1200).status_code == 200
    assert post(device(app_mod), 60).status_code == 429
    _, _, end = ft_mod.day_window(time.time(), dt.timezone.utc)
    app_mod.free_tier._clock = lambda: end + 1                  # next calendar day
    assert post(device(app_mod), 1200).status_code == 200


def test_monthly_allowance_still_resets_next_month_and_is_unchanged(app_mod):
    c = device(app_mod)
    assert post(c, 1200).status_code == 200
    assert c.get("/v1/allowance").json()["remaining_seconds"] == 0
    assert post(c, 10).status_code == 429                        # its own allowance is spent
    assert post(c, 10).json()["reason"] == "monthly_free_allowance_exceeded"
    import allowance as allowance_mod
    _, _, end = allowance_mod.month_window(time.time(), dt.timezone.utc)
    app_mod.allowance._clock = lambda: end + 1
    app_mod.free_tier._clock = lambda: end + 1                   # a new month is a new day too
    assert c.get("/v1/allowance").json()["remaining_seconds"] == 1200
    assert post(c, 600).status_code == 200


# ---- the free daily spend ceiling ------------------------------------------------
def test_free_daily_budget_refuses_before_any_provider_call(app_mod, monkeypatch):
    # 20 minutes cost about INR 7.57; a budget of 8 admits one and refuses the next.
    monkeypatch.setenv("FREE_DAILY_BUDGET_INR", "8")
    monkeypatch.setenv("FREE_DAILY_NETWORK_SECONDS", "100000")   # not the cap under test
    assert post(device(app_mod), 1200).status_code == 200
    before = calls(app_mod)
    r = post(device(app_mod), 1200)
    assert r.status_code == 429 and r.json()["reason"] == FREE_BUDGET
    assert r.json()["free_tier"]["free_daily_budget_inr"] == 8.0
    assert calls(app_mod) == before
    assert app_mod.free_tier.refusals[FREE_BUDGET] >= 1


def test_free_budget_is_separate_from_the_overall_budget(app_mod, monkeypatch):
    monkeypatch.setenv("FREE_DAILY_BUDGET_INR", "5")
    monkeypatch.setenv("DAILY_BUDGET_INR", "300")
    assert post(device(app_mod), 600).status_code == 200          # ~INR 3.8
    r = post(device(app_mod), 600)
    assert r.status_code == 429 and r.json()["reason"] == FREE_BUDGET
    # The overall budget is untouched and still has room: it is a different limit.
    assert app_mod.meter.spend_since(0) < 300


def test_overall_daily_budget_still_refuses_independently(app_mod, monkeypatch):
    monkeypatch.setenv("FREE_DAILY_BUDGET_INR", "1000")
    monkeypatch.setenv("FREE_DAILY_NETWORK_SECONDS", "100000")
    assert post(device(app_mod), 600).status_code == 200
    monkeypatch.setenv("DAILY_BUDGET_INR", "0.01")                # already exceeded
    importlib.reload(app_mod)
    monkeypatch.setattr(app_mod.deepgram, "transcribe", app_mod.dg)
    r = post(device(app_mod), 60)
    assert r.status_code == 503 and r.json()["reason"] == "budget"


def test_only_free_rows_count_toward_the_free_budget(app_mod):
    assert post(device(app_mod), 600).status_code == 200
    free, non_free = 0, 0
    for free_flag, _hash, success, cost, _s in rows(app_mod, "success = 1"):
        free += cost if free_flag else 0
        non_free += 0 if free_flag else cost
    assert free > 0 and non_free == 0
    # A row written outside the free tier (a future paid job) is ignored by both
    # free-tier limits.
    app_mod.meter.record("deepgram", 10, 3600.0, True, 200, est_cost_inr=22.7,
                         installation_id="paid", provider_called=True,
                         allowance_s=3600.0, free_tier=False, network_hash=None)
    stats = app_mod.free_tier.stats()
    assert stats["spend_inr"] < 22.7 and stats["jobs"] == 1


# ---- what the limits must NOT do ---------------------------------------------------
def test_status_and_allowance_requests_are_never_refused(app_mod):
    for _ in range(3):
        assert post(device(app_mod), 1200).status_code == 200
    c = device(app_mod)
    assert post(c, 60).status_code == 429                          # capped for transcription
    assert c.get("/v1/allowance").status_code == 200
    assert c.get("/v1/transcribe/status", headers={"Idempotency-Key": "x"}).status_code == 404
    assert c.get("/healthz").status_code == 200
    assert c.post("/v1/installations").status_code == 201


def test_local_transcription_is_unaffected(app_mod):
    """
    LOCAL runs on the phone and never reaches this backend: no registration, no
    request, nothing to cap. On the backend the free-tier gates live on exactly
    one route.
    """
    import inspect
    src = inspect.getsource(app_mod)
    assert src.count("free_tier.check(") == 1
    assert "free_tier.check(" in inspect.getsource(app_mod.transcribe)
    assert "free_tier.check(" not in inspect.getsource(app_mod.transcribe_status)
    assert "free_tier.check(" not in inspect.getsource(app_mod.allowance_status)


def test_legitimate_twenty_minute_monthly_use_is_untouched(app_mod):
    c = device(app_mod, "192.0.2.50")
    assert post(c, 300).status_code == 200
    assert post(c, 300).status_code == 200
    assert post(c, 600).status_code == 200                          # 20 minutes in three jobs
    u = c.get("/v1/allowance").json()
    assert (u["used_seconds"], u["remaining_seconds"]) == (1200, 0)
    assert calls(app_mod) == 3


def test_failover_still_counts_once_against_both_limits(app_mod):
    app_mod.dg.behaviours = [err(errors.TRANSIENT), err(errors.TRANSIENT)]
    c = device(app_mod)
    r = post(c, 600, key="job-failover")
    assert r.status_code == 200 and r.json()["provider"] == "assemblyai"
    assert app_mod.dg.calls == 2 and app_mod.aai.calls == 1
    assert c.get("/v1/allowance").json()["used_seconds"] == 600
    h = app_mod.free_tier.network_hash("203.0.113.7")
    assert app_mod.free_tier.network_seconds_today(h) == 600        # one job, not three


def test_idempotent_replay_still_counts_once_against_both_limits(app_mod):
    c = device(app_mod)
    assert post(c, 600, key="job-1").status_code == 200
    assert post(c, 600, key="job-1").json()["replayed"] is True
    assert calls(app_mod) == 1
    assert c.get("/v1/allowance").json()["used_seconds"] == 600
    h = app_mod.free_tier.network_hash("203.0.113.7")
    assert app_mod.free_tier.network_seconds_today(h) == 600


def test_a_refused_job_is_not_charged_to_anyone(app_mod):
    for _ in range(3):
        assert post(device(app_mod), 1200).status_code == 200
    c = device(app_mod)
    assert post(c, 600).status_code == 429
    assert c.get("/v1/allowance").json()["used_seconds"] == 0       # its allowance is intact
    h = app_mod.free_tier.network_hash("203.0.113.7")
    assert app_mod.free_tier.network_seconds_today(h) == 3600       # unchanged by the refusal


# ---- privacy of the stored handle -----------------------------------------------------
def test_no_client_address_is_stored_anywhere(app_mod):
    assert post(device(app_mod, "203.0.113.99"), 600).status_code == 200
    with sqlite3.connect(app_mod.db) as con:
        blob = " ".join(str(r) for r in con.execute("SELECT * FROM requests").fetchall())
    assert "203.0.113.99" not in blob
    stored = rows(app_mod, "success = 1")[0][1]
    assert stored and stored != "203.0.113.99" and len(stored) == 32


def test_the_address_hash_rotates_daily_so_it_is_not_a_persistent_identifier(app_mod):
    guard = app_mod.free_tier
    today = guard.network_hash("203.0.113.7")
    _, _, end = ft_mod.day_window(time.time(), dt.timezone.utc)
    guard._clock = lambda: end + 1
    tomorrow = guard.network_hash("203.0.113.7")
    assert today != tomorrow, "the same address must not hash to the same value on another day"
    # Old salts are deleted, so yesterday's stored hashes can no longer be linked.
    guard._clock = lambda: end + 1 + 86400 * (guard.salt_retention_days + 2)
    guard.network_hash("203.0.113.7")
    with sqlite3.connect(app_mod.db) as con:
        days = [r[0] for r in con.execute("SELECT day FROM address_salts").fetchall()]
    assert len(days) == 1


# ---- alerts and metrics -------------------------------------------------------------------
def test_free_budget_alert_is_logged_once_when_spend_approaches_the_ceiling(app_mod, monkeypatch, caplog):
    monkeypatch.setenv("FREE_DAILY_BUDGET_INR", "10")
    monkeypatch.setenv("FREE_BUDGET_ALERT_FRACTION", "0.5")
    with caplog.at_level("WARNING"):
        assert post(device(app_mod), 1200).status_code == 200      # ~7.6 INR, over 50% of 10
        assert post(device(app_mod), 60).status_code in (200, 429)
    alerts = [r for r in caplog.records if "FREE_BUDGET_ALERT" in r.getMessage()]
    assert len(alerts) == 1


def test_unusual_registration_activity_is_alerted(app_mod, monkeypatch, caplog):
    monkeypatch.setenv("FREE_REGISTRATION_ALERT_PER_HOUR", "3")
    with caplog.at_level("WARNING"):
        for _ in range(3):
            c = device(app_mod)
        assert post(c, 60).status_code == 200
    assert any("FREE_TIER_ACTIVITY_ALERT" in r.getMessage() for r in caplog.records)


def test_usage_endpoint_reports_free_tier_metrics(app_mod):
    assert post(device(app_mod), 600).status_code == 200
    c = device(app_mod)
    stats = c.get("/v1/usage").json()["free_tier"]
    assert stats["jobs"] == 1 and stats["minutes"] == 10.0
    assert stats["distinct_networks"] == 1 and stats["spend_inr"] > 0
    assert stats["daily_budget_inr"] == 100.0 and stats["network_daily_seconds"] == 3600
    assert stats["registrations_last_hour"] >= 2
    assert set(stats["refusals"]) == {NETWORK_CAP, FREE_BUDGET}


def test_defaults_are_sixty_minutes_and_one_hundred_rupees(app_mod):
    assert app_mod.free_tier.network_daily_seconds == 3600
    assert app_mod.free_tier.daily_budget_inr == 100.0
