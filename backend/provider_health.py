# -*- coding: utf-8 -*-
"""
Persisted provider health: which provider may be tried for new work, and when
an unavailable one is due for a re-check.

Stored in the metering database (one persistent volume holds all state), so a
restart during an outage keeps routing around the broken provider instead of
rediscovering it with a user's request.

State machine, per provider:

    HEALTHY --transient failure--> DEGRADED --N consecutive--> UNAVAILABLE(transient)
    HEALTHY/DEGRADED --429--------> COOLING_DOWN (until Retry-After)
    any --credential--------------> UNAVAILABLE(credential)
    any --quota/billing-----------> UNAVAILABLE(quota)
    UNAVAILABLE/COOLING_DOWN --recheck time reached--> eligible for ONE trial
    trial or any call --success---> HEALTHY
    trial --failure---------------> back to UNAVAILABLE, longer backoff
    input error (the audio) ------> no change

"Not configured" (no key) is computed from the environment, never stored.
Contains no audio, transcript, keyterm, token or credential.
"""

import os
import sqlite3
import threading
import time
from contextlib import closing
from dataclasses import dataclass
from typing import Callable, Dict, Optional

from providers import errors

HEALTHY = "healthy"
DEGRADED = "degraded"
COOLING_DOWN = "cooling_down"
UNAVAILABLE = "unavailable"


def _env_float(name: str, default: float) -> float:
    try:
        return float(os.environ.get(name, default))
    except (TypeError, ValueError):
        return default


@dataclass(frozen=True)
class HealthPolicy:
    transient_failures_to_trip: int = 3
    transient_recheck_s: float = 30.0
    rate_limit_default_s: float = 30.0
    rate_limit_max_s: float = 300.0
    credential_recheck_s: float = 300.0
    quota_recheck_s: float = 900.0
    max_recheck_s: float = 3600.0

    @staticmethod
    def from_env() -> "HealthPolicy":
        return HealthPolicy(
            transient_failures_to_trip=int(_env_float("PROVIDER_TRANSIENT_TRIP", 3)),
            transient_recheck_s=_env_float("PROVIDER_TRANSIENT_RECHECK_S", 30.0),
            credential_recheck_s=_env_float("PROVIDER_CREDENTIAL_RECHECK_S", 300.0),
            quota_recheck_s=_env_float("PROVIDER_QUOTA_RECHECK_S", 900.0),
            max_recheck_s=_env_float("PROVIDER_MAX_RECHECK_S", 3600.0),
        )


@dataclass
class Health:
    provider: str
    state: str = HEALTHY
    reason: Optional[str] = None
    consecutive_failures: int = 0
    unavailable_count: int = 0          # consecutive trips, drives backoff
    recheck_at: float = 0.0
    last_failure_at: float = 0.0
    last_success_at: float = 0.0

    def as_dict(self, now: float) -> dict:
        return {"state": self.state, "reason": self.reason,
                "consecutive_failures": self.consecutive_failures,
                "recheck_in_s": max(0, round(self.recheck_at - now, 1))
                if self.state in (UNAVAILABLE, COOLING_DOWN) else 0}


_SCHEMA = """
CREATE TABLE IF NOT EXISTS provider_health (
    provider             TEXT PRIMARY KEY,
    state                TEXT NOT NULL,
    reason               TEXT,
    consecutive_failures INTEGER NOT NULL DEFAULT 0,
    unavailable_count    INTEGER NOT NULL DEFAULT 0,
    recheck_at           REAL NOT NULL DEFAULT 0,
    last_failure_at      REAL NOT NULL DEFAULT 0,
    last_success_at      REAL NOT NULL DEFAULT 0
);
"""


class ProviderHealth:
    def __init__(self, path: str, policy: Optional[HealthPolicy] = None,
                 clock: Callable[[], float] = time.time):
        self._path = path
        self.policy = policy or HealthPolicy.from_env()
        self._clock = clock
        self._lock = threading.Lock()
        # A due provider gets ONE trial at a time; concurrent requests route
        # elsewhere rather than all piling onto a provider that may still be down.
        self._trials: set = set()
        with closing(sqlite3.connect(path, timeout=5)) as c, c:
            c.executescript(_SCHEMA)

    # ---- reads ------------------------------------------------------------
    def get(self, provider: str) -> Health:
        with self._lock, closing(sqlite3.connect(self._path, timeout=5)) as c:
            row = c.execute(
                "SELECT state, reason, consecutive_failures, unavailable_count,"
                " recheck_at, last_failure_at, last_success_at"
                " FROM provider_health WHERE provider = ?", (provider,)).fetchone()
        if not row:
            return Health(provider)
        return Health(provider, *row)

    def snapshot(self, providers) -> Dict[str, dict]:
        now = self._clock()
        return {p: self.get(p).as_dict(now) for p in providers}

    def is_available(self, provider: str) -> bool:
        """Eligible without a trial (healthy or merely degraded)."""
        return self.get(provider).state in (HEALTHY, DEGRADED)

    def is_due_for_recheck(self, provider: str) -> bool:
        h = self.get(provider)
        return h.state in (UNAVAILABLE, COOLING_DOWN) and h.recheck_at <= self._clock()

    def try_acquire(self, provider: str) -> bool:
        """
        True when [provider] may be called now. A healthy provider always may;
        an unavailable one only once its recheck time has passed, and only by
        one request at a time (released by record_success/record_failure).
        """
        h = self.get(provider)
        if h.state in (HEALTHY, DEGRADED):
            return True
        if h.recheck_at > self._clock():
            return False
        with self._lock:
            if provider in self._trials:
                return False
            self._trials.add(provider)
            return True

    def release_trial(self, provider: str) -> None:
        with self._lock:
            self._trials.discard(provider)

    def seconds_until_any(self, providers) -> float:
        now = self._clock()
        waits = []
        for p in providers:
            h = self.get(p)
            waits.append(0.0 if h.state in (HEALTHY, DEGRADED) else max(0.0, h.recheck_at - now))
        return min(waits) if waits else self.policy.transient_recheck_s

    # ---- transitions ------------------------------------------------------
    def record_success(self, provider: str) -> Health:
        h = Health(provider, HEALTHY, None, 0, 0, 0.0, self.get(provider).last_failure_at,
                   self._clock())
        self._put(h)
        self.release_trial(provider)
        return h

    def record_failure(self, provider: str, kind: str,
                       retry_after_s: Optional[float] = None) -> Health:
        now = self._clock()
        p = self.policy
        h = self.get(provider)
        was_down = h.state in (UNAVAILABLE, COOLING_DOWN)
        if kind in (errors.INPUT, errors.NOT_CONFIGURED):
            self.release_trial(provider)
            return h                                    # the audio, or no key: no health signal
        h.last_failure_at = now
        if kind == errors.RATE_LIMITED:
            wait = retry_after_s if retry_after_s is not None else p.rate_limit_default_s
            h.state, h.reason = COOLING_DOWN, errors.RATE_LIMITED
            # Exactly what the provider asked for (capped): padding it made
            # the router's short inline wait useless and forced a needless failover.
            h.recheck_at = now + min(max(wait, 0.0), p.rate_limit_max_s)
        elif kind in (errors.CREDENTIAL, errors.QUOTA):
            base = p.credential_recheck_s if kind == errors.CREDENTIAL else p.quota_recheck_s
            h.unavailable_count += 1
            h.state, h.reason = UNAVAILABLE, kind
            h.recheck_at = now + min(base * 2 ** (h.unavailable_count - 1), p.max_recheck_s)
        else:                                           # TRANSIENT / UNKNOWN
            h.consecutive_failures += 1
            if was_down or h.consecutive_failures >= p.transient_failures_to_trip:
                h.unavailable_count += 1
                h.state, h.reason = UNAVAILABLE, errors.TRANSIENT
                h.recheck_at = now + min(p.transient_recheck_s * 2 ** (h.unavailable_count - 1),
                                         p.max_recheck_s)
            else:
                h.state, h.reason = DEGRADED, errors.TRANSIENT
        self._put(h)
        self.release_trial(provider)
        return h

    def schedule_recheck_now(self, provider: str) -> None:
        """A cost-free probe looked good: let the next request trial it."""
        h = self.get(provider)
        if h.state in (UNAVAILABLE, COOLING_DOWN):
            h.recheck_at = min(h.recheck_at, self._clock())
            self._put(h)

    def _put(self, h: Health) -> None:
        with self._lock, closing(sqlite3.connect(self._path, timeout=5)) as c, c:
            c.execute(
                "INSERT INTO provider_health (provider, state, reason, consecutive_failures,"
                " unavailable_count, recheck_at, last_failure_at, last_success_at)"
                " VALUES (?,?,?,?,?,?,?,?) ON CONFLICT(provider) DO UPDATE SET"
                " state=excluded.state, reason=excluded.reason,"
                " consecutive_failures=excluded.consecutive_failures,"
                " unavailable_count=excluded.unavailable_count,"
                " recheck_at=excluded.recheck_at, last_failure_at=excluded.last_failure_at,"
                " last_success_at=excluded.last_success_at",
                (h.provider, h.state, h.reason, h.consecutive_failures, h.unavailable_count,
                 h.recheck_at, h.last_failure_at, h.last_success_at))
