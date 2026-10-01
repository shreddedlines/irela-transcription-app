# -*- coding: utf-8 -*-
"""
Owner entitlement: one person's own installations, exempt from the consumer
free tier but not from the safety limits.

IDENTIFICATION -- an owner claim code, never anything in the APK.
  The owner generates a long random code offline and keeps it. The server holds
  only its SHA-256, in OWNER_CLAIM_CODE_SHA256. An installation that presents
  the code (POST /v1/owner/claim, with its normal bearer token) becomes an
  owner installation. The code is compared in constant time, is never logged
  or stored, and nothing the app ships can make it an owner: an APK is public,
  so a flag or secret inside one would make every user an owner.

  * unset OWNER_CLAIM_CODE_SHA256 -> claiming is disabled (404), the default;
  * attempts are limited per installation, per network and globally BEFORE the
    code is checked, so guessing costs attempts whether or not it is right;
  * at most OWNER_MAX_INSTALLATIONS (default 2) owners may be active, so a
    leaked code cannot mint unlimited owners. Stale owners are revoked with
    owner_admin.py on the server -- there is no network admin API.

WHAT AN OWNER SKIPS: the free monthly allowance, the free per-network daily
cap and the free daily spend ceiling. Owner rows are metered free_tier=0 and
plan='owner', so they never consume the free tier's shared counters.

WHAT AN OWNER KEEPS: the kill switch, the global DAILY_BUDGET_INR, the per-file
duration and size limits, idempotency, and per-installation request limits of
its own (client_auth.Limits: 6/min, 150/day) -- plus the two ceilings here,
checked and reserved before any provider call:
  * OWNER_DAILY_SECONDS   (default 14400 = 4 h, about Rs 91 at Rs 22.7/h)
  * OWNER_MONTHLY_SECONDS (default 108000 = 30 h, about Rs 681)
A value of 0, a negative value or garbage falls back to the default: a safety
ceiling cannot be switched off by a typo.

Contains no audio, transcript, token, claim code or credential.
"""

import hashlib
import hmac
import logging
import os
import secrets
import sqlite3
import threading
import time
from contextlib import closing
from dataclasses import dataclass
from typing import Callable, Dict, Optional, Tuple

from allowance import _tz, month_window
from client_auth import OWNER_PLAN, InstallationStore, Rejection, SlidingWindow
from free_tier import day_window

log = logging.getLogger("proxy.owner")

DAILY_REASON = "owner_daily_limit_exceeded"
MONTHLY_REASON = "owner_monthly_limit_exceeded"

DEFAULT_DAILY_SECONDS = 4 * 3600          # 4 hours
DEFAULT_MONTHLY_SECONDS = 30 * 3600       # 30 hours
DEFAULT_MAX_INSTALLATIONS = 2

# Claim attempts per hour. The code is long enough that guessing is hopeless;
# these exist so a flood of guesses is cheap to refuse and visible in the log.
CLAIM_ATTEMPTS_PER_INSTALLATION_PER_HOUR = 5
CLAIM_ATTEMPTS_PER_NETWORK_PER_HOUR = 5
CLAIM_ATTEMPTS_GLOBAL_PER_HOUR = 20
MAX_CODE_LENGTH = 256


def _positive_int(name: str, default: int) -> int:
    """A positive integer from the environment; anything else is the default."""
    try:
        v = int(float(os.environ.get(name, default)))
    except (TypeError, ValueError):
        return default
    return v if v > 0 else default


# ---- claiming ---------------------------------------------------------------

class OwnerClaim:
    """Verifies the owner claim code and grants the owner plan."""

    def __init__(self, store: InstallationStore,
                 wall_clock: Callable[[], float] = time.time,
                 mono_clock: Callable[[], float] = time.monotonic):
        self._store = store
        self._now = wall_clock
        self._per_installation = SlidingWindow(3600.0, mono_clock)
        self._per_network = SlidingWindow(3600.0, mono_clock)
        self._global = SlidingWindow(3600.0, mono_clock)
        self._lock = threading.Lock()
        self.outcomes: Dict[str, int] = {"granted": 0, "already_owner": 0, "invalid": 0,
                                         "limited": 0, "owner_limit_reached": 0}

    # Read per call, like the kill switch.
    @staticmethod
    def _expected_hash() -> Optional[str]:
        raw = os.environ.get("OWNER_CLAIM_CODE_SHA256", "").strip().lower()
        return raw if len(raw) == 64 and all(ch in "0123456789abcdef" for ch in raw) else None

    @property
    def enabled(self) -> bool:
        return self._expected_hash() is not None

    @property
    def max_installations(self) -> int:
        return _positive_int("OWNER_MAX_INSTALLATIONS", DEFAULT_MAX_INSTALLATIONS)

    def claim(self, installation_id: str, network: Optional[str], code: Optional[str]) -> str:
        """
        Grants the owner plan to [installation_id] and returns "granted" or
        "already_owner"; otherwise raises Rejection. Never logs the code.
        """
        expected = self._expected_hash()
        if expected is None:
            raise Rejection(404, "not_found", "Not found.", False)

        # Attempts are charged before the code is looked at: a wrong guess and
        # a right one cost the same, so the limit cannot be probed.
        if not (self._per_installation.try_acquire(installation_id,
                                                   CLAIM_ATTEMPTS_PER_INSTALLATION_PER_HOUR)
                and self._per_network.try_acquire(network or "unknown",
                                                  CLAIM_ATTEMPTS_PER_NETWORK_PER_HOUR)
                and self._global.try_acquire("all", CLAIM_ATTEMPTS_GLOBAL_PER_HOUR)):
            self.outcomes["limited"] += 1
            log.warning("owner claim refused: too many attempts")
            raise Rejection(429, "owner_claim_limited",
                            "Too many attempts. Try again later.", False, 3600)

        presented = (code or "").strip()
        if not presented or len(presented) > MAX_CODE_LENGTH or not hmac.compare_digest(
                hashlib.sha256(presented.encode("utf-8")).hexdigest(), expected):
            self.outcomes["invalid"] += 1
            log.warning("owner claim refused: invalid code")
            raise Rejection(403, "owner_claim_invalid", "That code is not valid.", False)

        with self._lock:
            if self._store.plan_of(installation_id) == OWNER_PLAN:
                self.outcomes["already_owner"] += 1
                return "already_owner"
            if self._store.active_owner_count() >= self.max_installations:
                self.outcomes["owner_limit_reached"] += 1
                log.warning("owner claim refused: owner installation limit reached")
                raise Rejection(409, "owner_limit_reached",
                                "The owner code is already in use on the maximum number of "
                                "devices.", False)
            self._store.set_plan(installation_id, OWNER_PLAN, self._now())
        self.outcomes["granted"] += 1
        log.info("owner claim granted")            # no id, no code, no address
        return "granted"


# ---- the owner's safety ceilings ------------------------------------------

@dataclass(frozen=True)
class OwnerDecision:
    admitted: bool
    charge_seconds: float = 0.0
    reason: Optional[str] = None
    message: str = ""
    handle: Optional[str] = None


class OwnerGuard:
    """Daily and monthly audio-seconds ceilings for owner installations."""

    # Same accounting as the free allowance: successful, non-replayed jobs that
    # reached a provider, one per logical job however many attempts it took.
    _COUNTED = ("installation_id = ? AND plan = 'owner' AND success = 1 AND replayed = 0"
                " AND provider_called = 1 AND ts >= ? AND ts < ?")

    def __init__(self, path: str, clock: Callable[[], float] = time.time):
        self._path = path
        self._clock = clock
        self._lock = threading.Lock()
        self._reserved: Dict[Tuple[str, str], float] = {}          # (id, window) -> s
        self._handles: Dict[str, Tuple[Tuple[str, str], Tuple[str, str], float]] = {}
        self.refusals: Dict[str, int] = {DAILY_REASON: 0, MONTHLY_REASON: 0}

    @property
    def daily_seconds(self) -> int:
        return _positive_int("OWNER_DAILY_SECONDS", DEFAULT_DAILY_SECONDS)

    @property
    def monthly_seconds(self) -> int:
        return _positive_int("OWNER_MONTHLY_SECONDS", DEFAULT_MONTHLY_SECONDS)

    def now(self) -> float:
        return self._clock()

    def _connect(self):
        return sqlite3.connect(self._path, timeout=5)

    def used_seconds(self, installation_id: str, start: float, end: float) -> float:
        with closing(self._connect()) as c:
            row = c.execute(
                "SELECT COALESCE(SUM(s), 0) FROM ("
                " SELECT MAX(CASE WHEN allowance_s > 0 THEN allowance_s ELSE duration_s END) AS s"
                " FROM requests WHERE " + self._COUNTED +
                " GROUP BY COALESCE(idempotency_key, 'row:' || id))",
                (installation_id, start, end)).fetchone()
        return float(row[0] or 0.0)

    def _already_counted(self, installation_id: str, key: Optional[str],
                         start: float, end: float) -> bool:
        if not key:
            return False
        with closing(self._connect()) as c:
            return c.execute("SELECT 1 FROM requests WHERE " + self._COUNTED +
                             " AND idempotency_key = ? LIMIT 1",
                             (installation_id, start, end, key)).fetchone() is not None

    def admit(self, installation_id: str, requested_seconds: float,
              idempotency_key: Optional[str] = None) -> OwnerDecision:
        """
        Decide and, when admitted, reserve atomically. The caller MUST pass the
        returned handle to [release] once the job's metering row is written.
        """
        now = self.now()
        day, d_start, d_end = day_window(now, _tz())
        month, m_start, m_end = month_window(now, _tz())
        # A job this month already counted (a retry re-run after the
        # idempotency cache was lost) is not charged again.
        charge = 0.0 if self._already_counted(installation_id, idempotency_key, m_start, m_end) \
            else max(0.0, requested_seconds)
        used_day = self.used_seconds(installation_id, d_start, d_end)
        used_month = self.used_seconds(installation_id, m_start, m_end)
        dkey, mkey = (installation_id, "d:" + day), (installation_id, "m:" + month)
        with self._lock:
            r_day = self._reserved.get(dkey, 0.0)
            r_month = self._reserved.get(mkey, 0.0)
            if used_day + r_day + charge > self.daily_seconds + 1e-6:
                reason = DAILY_REASON
            elif used_month + r_month + charge > self.monthly_seconds + 1e-6:
                reason = MONTHLY_REASON
            else:
                reason = None
            if reason is None:
                handle = f"{day}:{secrets.token_hex(8)}"
                if charge > 0:
                    self._reserved[dkey] = r_day + charge
                    self._reserved[mkey] = r_month + charge
                self._handles[handle] = (dkey, mkey, charge)
        if reason is None:
            return OwnerDecision(True, charge, handle=handle)
        self.refusals[reason] += 1
        log.warning("owner ceiling reached reason=%s requested_s=%.0f", reason, requested_seconds)
        if reason == DAILY_REASON:
            message = "Today's usage limit for this device has been reached. It resets tomorrow."
        else:
            message = "This month's usage limit for this device has been reached."
        return OwnerDecision(False, 0.0, reason, message)

    def release(self, handle: Optional[str]) -> None:
        if not handle:
            return
        with self._lock:
            entry = self._handles.pop(handle, None)
            if not entry:
                return
            dkey, mkey, charge = entry
            for key in (dkey, mkey):
                left = self._reserved.get(key, 0.0) - charge
                if left > 1e-6:
                    self._reserved[key] = left
                else:
                    self._reserved.pop(key, None)

    def usage(self, installation_id: str) -> dict:
        """Operator view for one owner installation (owner_admin.py, /v1/usage)."""
        now = self.now()
        day, d_start, d_end = day_window(now, _tz())
        month, m_start, m_end = month_window(now, _tz())
        return {"day": day, "day_seconds": round(self.used_seconds(installation_id, d_start, d_end), 2),
                "daily_seconds_limit": self.daily_seconds,
                "month": month,
                "month_seconds": round(self.used_seconds(installation_id, m_start, m_end), 2),
                "monthly_seconds_limit": self.monthly_seconds}
