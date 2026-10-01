# -*- coding: utf-8 -*-
"""
Provider router: failover, bounded retry, persisted health, recovery.

No network and no credentials. Both provider modules are replaced by scripted
fakes; the recurring assertions are which provider was called, how many times,
and what the client was told.
"""
import asyncio
import importlib
import os
import sys
import tempfile
import threading

import pytest
from fastapi.testclient import TestClient

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import provider_health as ph          # noqa: E402
from providers import errors          # noqa: E402


def wav(seconds: float = 1.0, rate: int = 16000) -> bytes:
    n = int(seconds * rate) * 2
    return (b"RIFF" + (36 + n).to_bytes(4, "little") + b"WAVEfmt " +
            (16).to_bytes(4, "little") + (1).to_bytes(2, "little") +
            (1).to_bytes(2, "little") + rate.to_bytes(4, "little") +
            (rate * 2).to_bytes(4, "little") + (2).to_bytes(2, "little") +
            (16).to_bytes(2, "little") + b"data" + n.to_bytes(4, "little") + b"\x00" * n)


class Script:
    """A fake provider: pops one behaviour per call; the last one repeats."""
    def __init__(self, name):
        self.name = name
        self.behaviours = ["ok"]
        self.calls = 0
        self.gate = None                     # threading.Event to hold a call open

    async def __call__(self, audio, content_type, language=None, keyterms=None, timeout_s=300.0):
        self.calls += 1
        b = self.behaviours.pop(0) if len(self.behaviours) > 1 else self.behaviours[0]
        if self.gate is not None:
            while not self.gate.is_set():
                await asyncio.sleep(0.01)
        if b == "ok":
            return {"text": f"{self.name} transcript", "provider": self.name,
                    "detected_language": "hi", "provider_ms": 0, "audio_duration_s": 1.0}
        if isinstance(b, errors.ProviderError):
            raise b
        raise AssertionError(f"unknown behaviour {b}")


def err(kind, status=502, retry_after=None):
    return errors.ProviderError(status, f"fake {kind}", kind=kind, retry_after_s=retry_after)


@pytest.fixture()
def env(monkeypatch):
    db = os.path.join(tempfile.mkdtemp(), "m.sqlite3")
    for k, v in {"METERING_DB": db, "TRANSCRIPTION_ENABLED": "true",
                 "DEEPGRAM_API_KEY": "dg-fake", "ASSEMBLYAI_API_KEY": "aai-fake",
                 "CLIENT_REQUESTS_PER_MINUTE": "1000", "CLIENT_TRANSCRIPTIONS_PER_DAY": "1000",
                 "ROUTER_BACKOFF_BASE_S": "0.01", "PROVIDER_PROBE_INTERVAL_S": "0",
                 "HANDLER_WAIT_S": "5"}.items():
        monkeypatch.setenv(k, v)
    return monkeypatch


def boot(monkeypatch):
    """A fresh app process against the environment (and database) in `env`."""
    import app as app_mod
    importlib.reload(app_mod)
    dg, aai = Script("deepgram"), Script("assemblyai")
    monkeypatch.setattr(app_mod.deepgram, "transcribe", dg)
    monkeypatch.setattr(app_mod.assemblyai, "transcribe", aai)
    c = TestClient(app_mod.app)
    c.__enter__()                          # one event loop for in-flight work
    c.headers["Authorization"] = "Bearer " + c.post("/v1/installations").json()["token"]
    c.app_mod, c.dg, c.aai = app_mod, dg, aai
    return c


def post(c, key=None, audio=None):
    headers = {"Idempotency-Key": key} if key else {}
    return c.post("/v1/transcribe", headers=headers,
                  files={"file": ("a.wav", audio or wav(), "audio/wav")},
                  data={"provider": "deepgram", "language": "hi"})


def state(c, provider):
    return c.get("/healthz").json()["provider_health"][provider]["state"]


# ---- selection ---------------------------------------------------------------

def test_deepgram_healthy_is_selected(env):
    c = boot(env)
    r = post(c)
    assert r.status_code == 200 and r.json()["provider"] == "deepgram"
    assert (c.dg.calls, c.aai.calls) == (1, 0)


def test_client_provider_hint_does_not_choose_the_provider(env):
    c = boot(env)
    r = c.post("/v1/transcribe", files={"file": ("a.wav", wav(), "audio/wav")},
               data={"provider": "assemblyai"})
    assert r.json()["provider"] == "deepgram" and c.aai.calls == 0


def test_auto_hint_is_accepted_and_unknown_hint_rejected(env):
    c = boot(env)
    assert c.post("/v1/transcribe", files={"file": ("a.wav", wav(), "audio/wav")},
                  data={"provider": "auto"}).status_code == 200
    assert c.post("/v1/transcribe", files={"file": ("a.wav", wav(), "audio/wav")},
                  data={"provider": "whisper"}).status_code == 400


# ---- transient failures ------------------------------------------------------

def test_deepgram_timeout_is_retried_a_bounded_number_of_times(env):
    c = boot(env)
    c.dg.behaviours = [err(errors.TRANSIENT, 504), "ok"]
    r = post(c)
    assert r.status_code == 200 and r.json()["provider"] == "deepgram"
    assert (c.dg.calls, c.aai.calls) == (2, 0)
    assert state(c, "deepgram") == "healthy"


def test_deepgram_5xx_retries_then_fails_over(env):
    c = boot(env)
    c.dg.behaviours = [err(errors.TRANSIENT, 502)]
    r = post(c)
    assert r.status_code == 200 and r.json()["provider"] == "assemblyai"
    assert c.dg.calls == 2                                 # bounded: one retry, not forever
    assert c.aai.calls == 1
    assert state(c, "deepgram") == "degraded"


def test_repeated_transient_failures_trip_the_circuit(env):
    c = boot(env)
    c.dg.behaviours = [err(errors.TRANSIENT, 502)]
    post(c); post(c)                                       # 4 failures >= trip at 3
    assert state(c, "deepgram") == "unavailable"
    before = c.dg.calls
    assert post(c).json()["provider"] == "assemblyai"
    assert c.dg.calls == before                            # skipped while unavailable


# ---- credential / quota / rate limit ------------------------------------------

def test_deepgram_credential_failure_fails_over_and_marks_unhealthy(env):
    c = boot(env)
    c.dg.behaviours = [err(errors.CREDENTIAL, 503)]
    r = post(c)
    assert r.json()["provider"] == "assemblyai"
    assert c.dg.calls == 1                                 # no retry of a rejected credential
    h = c.get("/healthz").json()["provider_health"]["deepgram"]
    assert h["state"] == "unavailable" and h["reason"] == "credential"
    post(c)
    assert c.dg.calls == 1


def test_deepgram_quota_exhaustion_routes_new_work_to_assemblyai(env):
    c = boot(env)
    c.dg.behaviours = [err(errors.QUOTA, 503)]
    for _ in range(3):
        assert post(c).json()["provider"] == "assemblyai"
    assert c.dg.calls == 1                                 # one call, no key rotation
    h = c.get("/healthz").json()["provider_health"]["deepgram"]
    assert h["reason"] == "quota" and h["recheck_in_s"] > 0


def test_rate_limit_with_short_retry_after_waits_on_the_same_provider(env):
    c = boot(env)
    c.dg.behaviours = [err(errors.RATE_LIMITED, 429, retry_after=0.05), "ok"]
    assert post(c).json()["provider"] == "deepgram"
    assert (c.dg.calls, c.aai.calls) == (2, 0)


def test_rate_limit_with_long_retry_after_fails_over_and_cools_down(env):
    c = boot(env)
    c.dg.behaviours = [err(errors.RATE_LIMITED, 429, retry_after=120)]
    assert post(c).json()["provider"] == "assemblyai"
    assert c.dg.calls == 1
    h = c.get("/healthz").json()["provider_health"]["deepgram"]
    assert h["state"] == "cooling_down" and 100 < h["recheck_in_s"] <= 120


def test_assemblyai_success_after_deepgram_failure_is_metered_as_failover(env):
    c = boot(env)
    c.dg.behaviours = [err(errors.QUOTA, 503)]
    r = post(c)
    assert r.status_code == 200 and r.json()["text"] == "assemblyai transcript"
    usage = c.get("/v1/usage").json()
    assert usage["by_provider"] == {"assemblyai": 1} and usage["successes"] == 1
    import sqlite3
    with sqlite3.connect(os.environ["METERING_DB"]) as db:
        attempts, failed_over = db.execute(
            "SELECT attempts, failed_over FROM requests WHERE success = 1").fetchone()
    assert attempts == 2 and failed_over == 1


# ---- recovery -----------------------------------------------------------------

def test_deepgram_is_preferred_again_after_recovery(env):
    c = boot(env)
    c.dg.behaviours = [err(errors.QUOTA, 503), "ok"]
    assert post(c).json()["provider"] == "assemblyai"
    # Recheck time passes (e.g. credits topped up).
    h = c.app_mod.health
    rec = h.get("deepgram"); rec.recheck_at = 0; h._put(rec)
    assert post(c).json()["provider"] == "deepgram"        # the trial succeeds
    assert state(c, "deepgram") == "healthy"
    assert post(c).json()["provider"] == "deepgram"        # and stays preferred
    assert c.aai.calls == 1


def test_failed_trial_backs_off_longer(env):
    c = boot(env)
    c.dg.behaviours = [err(errors.QUOTA, 503)]
    post(c)
    h = c.app_mod.health
    first = h.get("deepgram").recheck_at
    rec = h.get("deepgram"); rec.recheck_at = 0; h._put(rec)
    post(c)                                                # trial fails
    assert c.dg.calls == 2
    assert h.get("deepgram").unavailable_count == 2
    assert h.get("deepgram").recheck_at - first > 100      # doubled backoff


def test_probe_ok_brings_recheck_forward_but_does_not_mark_healthy(env):
    c = boot(env)
    c.dg.behaviours = [err(errors.CREDENTIAL, 503), "ok"]
    post(c)

    async def ok_probe(timeout_s=10.0):
        return "ok"
    env.setattr(c.app_mod.deepgram, "probe", ok_probe)
    asyncio.run(c.app_mod.probe_unavailable_providers())
    assert state(c, "deepgram") == "unavailable"           # a probe alone proves nothing
    assert post(c).json()["provider"] == "deepgram"        # but the next request trials it
    assert state(c, "deepgram") == "healthy"


# ---- total outage -----------------------------------------------------------------

def test_both_providers_unavailable_is_a_retryable_503_not_a_crash(env):
    c = boot(env)
    c.dg.behaviours = [err(errors.TRANSIENT, 502)]
    c.aai.behaviours = [err(errors.TRANSIENT, 502)]
    r = post(c, key="job-outage")
    assert r.status_code == 503
    j = r.json()
    assert j["retryable"] is True and j["reason"] == "providers_unavailable"
    assert int(r.headers["Retry-After"]) >= 5 and j["retry_after_s"] >= 5
    assert c.dg.calls == 2 and c.aai.calls == 2            # bounded on both
    # Nothing was cached: once a provider is back, the same job key transcribes.
    c.dg.behaviours = ["ok"]
    h = c.app_mod.health
    for p in ("deepgram", "assemblyai"):
        rec = h.get(p); rec.recheck_at = 0; h._put(rec)
    assert post(c, key="job-outage").json()["provider"] == "deepgram"


def test_no_provider_configured_is_terminal(env):
    env.delenv("DEEPGRAM_API_KEY"); env.delenv("ASSEMBLYAI_API_KEY")
    c = boot(env)
    r = post(c)
    assert r.status_code == 503 and r.json()["retryable"] is False
    assert r.json()["reason"] == "not_configured"
    assert c.dg.calls == c.aai.calls == 0


def test_only_backup_configured_still_serves(env):
    env.delenv("DEEPGRAM_API_KEY")
    c = boot(env)
    assert post(c).json()["provider"] == "assemblyai" and c.dg.calls == 0


def test_process_restart_during_outage_keeps_routing_around_deepgram(env):
    c = boot(env)
    c.dg.behaviours = [err(errors.QUOTA, 503)]
    post(c)
    c.__exit__(None, None, None)
    c2 = boot(env)                                         # new process, same database
    assert c2.get("/healthz").json()["provider_health"]["deepgram"]["reason"] == "quota"
    assert post(c2).json()["provider"] == "assemblyai"
    assert c2.dg.calls == 0                                # not rediscovered with a user's request


# ---- idempotency across failover ------------------------------------------------------

def test_retry_after_success_on_backup_replays_without_any_provider_call(env):
    c = boot(env)
    c.dg.behaviours = [err(errors.QUOTA, 503)]
    first = post(c, key="job-1")
    calls = (c.dg.calls, c.aai.calls)
    # Deepgram recovers before the client's retry arrives.
    h = c.app_mod.health
    rec = h.get("deepgram"); rec.recheck_at = 0; h._put(rec)
    c.dg.behaviours = ["ok"]
    second = post(c, key="job-1")
    assert second.json()["replayed"] is True
    assert second.json()["text"] == first.json()["text"] == "assemblyai transcript"
    assert (c.dg.calls, c.aai.calls) == calls              # never transcribed twice


def test_retry_while_first_attempt_is_still_running_joins_it(env):
    env.setenv("HANDLER_WAIT_S", "0.3")
    c = boot(env)
    c.dg.gate = threading.Event()                          # provider call held open
    r1 = post(c, key="job-slow")
    assert r1.status_code == 503 and r1.json()["reason"] == "processing"
    assert r1.json()["retryable"] is True
    r2 = post(c, key="job-slow")                           # client retry, still running
    assert r2.json()["reason"] == "processing"
    c.dg.gate.set()
    import time as _t
    for _ in range(50):
        r3 = post(c, key="job-slow")
        if r3.status_code == 200:
            break
        _t.sleep(0.05)
    assert r3.status_code == 200
    assert c.dg.calls == 1                                 # one provider call for three requests
    assert c.aai.calls == 0


# ---- status polling: no re-upload -----------------------------------------------------------

def status(c, key):
    return c.get("/v1/transcribe/status", headers={"Idempotency-Key": key})


def test_status_poll_joins_running_work_without_reupload(env):
    env.setenv("HANDLER_WAIT_S", "0.2")
    env.setenv("STATUS_WAIT_S", "0.2")
    c = boot(env)
    c.dg.gate = threading.Event()
    assert post(c, key="job-poll").json()["reason"] == "processing"
    s1 = status(c, "job-poll")
    assert s1.status_code == 503 and s1.json()["reason"] == "processing"
    c.dg.gate.set()
    import time as _t
    for _ in range(50):
        s2 = status(c, "job-poll")
        if s2.status_code == 200:
            break
        _t.sleep(0.05)
    assert s2.status_code == 200 and s2.json()["text"] == "deepgram transcript"
    assert c.dg.calls == 1


def test_status_of_unknown_job_is_404_so_the_client_uploads(env):
    c = boot(env)
    r = status(c, "never-sent")
    assert r.status_code == 404 and r.json()["reason"] == "unknown_job"


def test_status_reports_a_recent_provider_outage(env):
    c = boot(env)
    c.dg.behaviours = [err(errors.TRANSIENT, 502)]
    c.aai.behaviours = [err(errors.TRANSIENT, 502)]
    post(c, key="job-down")
    r = status(c, "job-down")
    assert r.status_code == 503 and r.json()["reason"] == "providers_unavailable"
    assert "Retry-After" in r.headers
    assert c.dg.calls == 2                                  # the poll called nobody


def test_status_needs_a_token_but_not_quota(env):
    env.setenv("CLIENT_TRANSCRIPTIONS_PER_DAY", "1")
    env.setenv("CLIENT_REQUESTS_PER_MINUTE", "1")
    c = boot(env)
    post(c, key="job-q")
    for _ in range(5):                                      # well past the per-minute limit
        assert status(c, "job-q").status_code == 200
    anon = c.get("/v1/transcribe/status", headers={"Idempotency-Key": "job-q",
                                                   "Authorization": "Bearer nope"})
    assert anon.status_code == 401


def test_status_is_scoped_per_installation(env):
    c = boot(env)
    post(c, key="job-private")
    other = c.post("/v1/installations").json()["token"]
    r = c.get("/v1/transcribe/status", headers={"Idempotency-Key": "job-private",
                                                "Authorization": f"Bearer {other}"})
    assert r.status_code == 404                             # never another installation's transcript


# ---- input errors never fail over ----------------------------------------------------------

def test_invalid_audio_rejected_by_deepgram_does_not_switch_provider(env):
    c = boot(env)
    c.dg.behaviours = [err(errors.INPUT, 422)]
    r = post(c)
    assert r.status_code == 422 and r.json()["retryable"] is False
    assert r.json()["reason"] == "audio_rejected"
    assert (c.dg.calls, c.aai.calls) == (1, 0)
    assert state(c, "deepgram") == "healthy"               # the audio, not the provider


def test_mip_opt_out_invariant_violation_never_fails_over(env):
    c = boot(env)

    async def violates(**kw):
        raise AssertionError("mip_opt_out")
    env.setattr(c.app_mod.deepgram, "transcribe", violates)
    r = post(c)
    assert r.status_code == 500 and c.aai.calls == 0


def test_kill_switch_still_blocks_every_provider(env):
    c = boot(env)
    env.setenv("TRANSCRIPTION_ENABLED", "false")
    assert post(c).json()["reason"] == "disabled"
    assert c.dg.calls == c.aai.calls == 0


# ---- health state machine, directly ----------------------------------------------------------

class Clock:
    def __init__(self): self.t = 1000.0
    def __call__(self): return self.t


def health(tmp_path, **policy):
    clk = Clock()
    return ph.ProviderHealth(str(tmp_path / "h.sqlite3"),
                             ph.HealthPolicy(**policy), clock=clk), clk


def test_state_transitions(tmp_path):
    h, clk = health(tmp_path, transient_failures_to_trip=2, transient_recheck_s=30,
                    quota_recheck_s=900)
    assert h.get("deepgram").state == ph.HEALTHY
    h.record_failure("deepgram", errors.TRANSIENT)
    assert h.get("deepgram").state == ph.DEGRADED
    h.record_failure("deepgram", errors.TRANSIENT)
    assert h.get("deepgram").state == ph.UNAVAILABLE
    assert not h.try_acquire("deepgram")
    clk.t += 31
    assert h.try_acquire("deepgram")                       # the single trial
    assert not h.try_acquire("deepgram")                   # a second concurrent trial is refused
    h.record_success("deepgram")
    assert h.get("deepgram").state == ph.HEALTHY and h.try_acquire("deepgram")
    h.record_failure("deepgram", errors.QUOTA)
    assert h.get("deepgram").reason == errors.QUOTA
    assert h.seconds_until_any(["deepgram"]) == pytest.approx(900)
    h.record_failure("deepgram", errors.INPUT)             # the audio: no signal
    assert h.get("deepgram").reason == errors.QUOTA


def test_health_survives_reopen(tmp_path):
    h, clk = health(tmp_path)
    h.record_failure("deepgram", errors.CREDENTIAL)
    again = ph.ProviderHealth(str(tmp_path / "h.sqlite3"), clock=clk)
    assert again.get("deepgram").state == ph.UNAVAILABLE


def test_health_table_holds_no_content_or_credentials(env):
    c = boot(env)
    c.dg.behaviours = [err(errors.CREDENTIAL, 503)]
    post(c)
    import sqlite3
    with sqlite3.connect(os.environ["METERING_DB"]) as db:
        dump = "\n".join(db.iterdump())
    assert "dg-fake" not in dump and "aai-fake" not in dump and "transcript" not in dump.lower().replace("assemblyai transcript", "")


# ---- provider error classification ------------------------------------------------------------

@pytest.mark.parametrize("status,hint,kind", [
    (401, "", errors.CREDENTIAL), (403, "INSUFFICIENT_PERMISSIONS", errors.CREDENTIAL),
    (402, "", errors.QUOTA), (403, "Insufficient credit balance", errors.QUOTA),
    (429, "", errors.RATE_LIMITED), (500, "", errors.TRANSIENT), (503, "", errors.TRANSIENT),
    (400, "", errors.INPUT), (413, "", errors.INPUT), (415, "", errors.INPUT),
    (418, "", errors.UNKNOWN)])
def test_http_classification(status, hint, kind):
    assert errors.classify_http("deepgram", status, body_hint=hint).kind == kind


def test_retry_after_parsed_for_rate_limits():
    e = errors.classify_http("deepgram", 429, retry_after="12")
    assert e.retry_after_s == 12 and e.retryable is True
