# -*- coding: utf-8 -*-
"""
Abuse protection for the FREE tier, on top of the per-installation monthly
allowance (allowance.py).

The monthly allowance is per installation, and an installation is free to
create: clearing app data or reinstalling registers a new one and earns another
20 minutes. Nothing the app can hold prevents that -- an analysis of installation
identity concluded the only honest mitigations are server-side and coarse. Two
are implemented here, and both refuse a job BEFORE any provider is contacted:

  * a daily cap on free minutes per client network address (default 60 min), so
    the reset loop stops paying out from one network;
  * a daily spend ceiling for free traffic (default INR 100), separate from the
    overall DAILY_BUDGET_INR, so free-tier abuse cannot consume the budget that
    serves everyone.

PRIVACY. The client address is never stored. What is stored on the metering row
is sha256(daily salt || address), truncated, and the salt is a fresh 32 random
bytes per day, kept only for a few days and then deleted. Once a day's salt is
gone its hashes cannot be linked to an address or to any other day's hashes, so
the value cannot serve as a persistent identifier. No device identifier of any
kind is involved -- the app sends none, and none is asked for.

The address itself comes from the ASGI scope (uvicorn's --proxy-headers with
--forwarded-allow-ips, i.e. the local TLS proxy). Client-supplied forwarding
headers are never read here.
"""

import datetime as _dt
import hashlib
import logging
import os
import secrets
import sqlite3
import threading
import time
from contextlib import closing
from dataclasses import dataclass
from typing import Callable, Dict, Optional, Tuple

from allowance import _tz  # one timezone decides both the month and the day

log = logging.getLogger("proxy.free_tier")

NETWORK_CAP_REASON = "free_daily_network_cap_exceeded"
BUDGET_REASON = "free_daily_budget_exceeded"

DEFAULT_NETWORK_DAILY_SECONDS = 3600         # 60 minutes
DEFAULT_FREE_DAILY_BUDGET_INR = 100.0
DEFAULT_SALT_RETENTION_DAYS = 7
DEFAULT_BUDGET_ALERT_FRACTION = 0.8
DEFAULT_REGISTRATION_ALERT_PER_HOUR = 50

_SCHEMA = """
CREATE TABLE IF NOT EXISTS address_salts (
    day        TEXT PRIMARY KEY,
    salt       BLOB NOT NULL,
    created_at REAL NOT NULL
);
"""


def _env_float(name: str, default: float) -> float:
    try:
        v = float(os.environ.get(name, default))
    except (TypeError, ValueError):
        return default
    return v if v >= 0 else default


def _env_int(name: str, default: int) -> int:
    try:
        v = int(float(os.environ.get(name, default)))
    except (TypeError, ValueError):
        return default
    return v if v >= 0 else default


def day_window(now: float, tz: _dt.tzinfo) -> Tuple[str, float, float]:
    """("YYYY-MM-DD", start, end) of the calendar day containing [now]."""
    local = _dt.datetime.fromtimestamp(now, tz)
    start = _dt.datetime(local.year, local.month, local.day, tzinfo=tz)
    end = start + _dt.timedelta(days=1)
    return start.strftime("%Y-%m-%d"), start.timestamp(), end.timestamp()


@dataclass(frozen=True)
class Verdict:
    admitted: bool
    reason: Optional[str] = None
    message: str = ""
    detail: Optional[dict] = None


class FreeTierGuard:
    """Daily per-network and per-day-spend limits for free transcription."""

    def __init__(self, path: str, clock: Callable[[], float] = time.time):
        self._path = path
        self._clock = clock
        self._lock = threading.Lock()
        # Seconds and rupees of free jobs that are running right now, so two
        # concurrent jobs cannot both fit under the same remaining limit.
        self._reserved_seconds: Dict[Tuple[str, str], float] = {}
        self._reserved_inr: Dict[str, float] = {}
        self._reservation_keys: Dict[str, Tuple[Tuple[str, str], float]] = {}
        self._budget_alerted: Optional[str] = None      # day already logged
        self._activity_alerted: Optional[str] = None
        self.refusals: Dict[str, int] = {NETWORK_CAP_REASON: 0, BUDGET_REASON: 0}
        with closing(sqlite3.connect(self._path, timeout=5)) as c, c:
            c.executescript(_SCHEMA)

    # ---- configuration (read per call, like the kill switch) ----------------
    @property
    def network_daily_seconds(self) -> int:
        return _env_int("FREE_DAILY_NETWORK_SECONDS", DEFAULT_NETWORK_DAILY_SECONDS)

    @property
    def daily_budget_inr(self) -> float:
        return _env_float("FREE_DAILY_BUDGET_INR", DEFAULT_FREE_DAILY_BUDGET_INR)

    @property
    def salt_retention_days(self) -> int:
        return max(1, _env_int("FREE_TIER_SALT_RETENTION_DAYS", DEFAULT_SALT_RETENTION_DAYS))

    def now(self) -> float:
        return self._clock()

    def day(self, now: Optional[float] = None) -> Tuple[str, float, float]:
        return day_window(self.now() if now is None else now, _tz())

    # ---- the salted, rotating address hash ----------------------------------
    def _salt_for(self, day: str) -> bytes:
        with self._lock, closing(sqlite3.connect(self._path, timeout=5)) as c, c:
            row = c.execute("SELECT salt FROM address_salts WHERE day = ?", (day,)).fetchone()
            if row:
                return row[0]
            salt = secrets.token_bytes(32)
            c.execute("INSERT OR IGNORE INTO address_salts (day, salt, created_at)"
                      " VALUES (?,?,?)", (day, salt, self.now()))
            row = c.execute("SELECT salt FROM address_salts WHERE day = ?", (day,)).fetchone()
            # Delete salts old enough that their hashes are now unlinkable.
            cutoff = _dt.datetime.fromtimestamp(self.now(), _tz()).date() - \
                _dt.timedelta(days=self.salt_retention_days)
            c.execute("DELETE FROM address_salts WHERE day < ?", (cutoff.strftime("%Y-%m-%d"),))
        return row[0]

    def network_hash(self, address: Optional[str], now: Optional[float] = None) -> Optional[str]:
        """
        An opaque per-day handle for a client address, or None when there is no
        usable address (the address itself is never stored or logged).
        """
        addr = (address or "").strip()
        if not addr or addr == "unknown":
            return None
        day, _, _ = self.day(now)
        digest = hashlib.sha256(self._salt_for(day) + addr.encode("utf-8")).hexdigest()
        return digest[:32]

    # ---- accounting ---------------------------------------------------------
    def _connect(self):
        return sqlite3.connect(self._path, timeout=5)

    _FREE_COUNTED = ("free_tier = 1 AND success = 1 AND replayed = 0 AND provider_called = 1"
                     " AND ts >= ? AND ts < ?")

    def network_seconds_today(self, network_hash: str, now: Optional[float] = None) -> float:
        _, start, end = self.day(now)
        with closing(self._connect()) as c:
            row = c.execute(
                "SELECT COALESCE(SUM(s), 0) FROM ("
                " SELECT MAX(CASE WHEN allowance_s > 0 THEN allowance_s ELSE duration_s END) AS s"
                " FROM requests WHERE " + self._FREE_COUNTED + " AND network_hash = ?"
                " GROUP BY COALESCE(idempotency_key, 'row:' || id))",
                (start, end, network_hash)).fetchone()
        return float(row[0] or 0.0)

    def spend_today_inr(self, now: Optional[float] = None) -> float:
        _, start, end = self.day(now)
        with closing(self._connect()) as c:
            row = c.execute(
                "SELECT COALESCE(SUM(est_cost_inr), 0) FROM requests WHERE " + self._FREE_COUNTED,
                (start, end)).fetchone()
        return float(row[0] or 0.0)

    def stats(self, now: Optional[float] = None) -> dict:
        day, start, end = self.day(now)
        with closing(self._connect()) as c:
            rows, seconds, networks = c.execute(
                "SELECT COUNT(*), COALESCE(SUM(CASE WHEN allowance_s > 0 THEN allowance_s"
                " ELSE duration_s END), 0), COUNT(DISTINCT network_hash)"
                " FROM requests WHERE " + self._FREE_COUNTED, (start, end)).fetchone()
        with self._lock:
            reserved_inr = sum(self._reserved_inr.values())
        spend = self.spend_today_inr(now)
        return {"day": day,
                "jobs": rows,
                "minutes": round(seconds / 60.0, 2),
                "distinct_networks": networks,
                "spend_inr": round(spend, 4),
                "in_progress_inr": round(reserved_inr, 4),
                "daily_budget_inr": self.daily_budget_inr,
                "network_daily_seconds": self.network_daily_seconds,
                "refusals": dict(self.refusals)}

    # ---- admission -----------------------------------------------------------
    def check(self, network_hash: Optional[str], requested_seconds: float,
              projected_inr: float) -> Verdict:
        """
        Whether a free job may start. Reserves nothing; call [reserve] once the
        job is admitted by every gate.
        """
        now = self.now()
        day, _, _ = self.day(now)
        budget = self.daily_budget_inr
        with self._lock:
            reserved_inr = sum(self._reserved_inr.values())
            reserved_seconds = self._reserved_seconds.get((network_hash or "", day), 0.0)
        spend = self.spend_today_inr(now)
        if budget > 0 and spend + reserved_inr + projected_inr > budget + 1e-9:
            self.refusals[BUDGET_REASON] += 1
            self._alert_budget(spend, budget, day, refused=True)
            return Verdict(False, BUDGET_REASON,
                           "Free transcription has reached today's limit. Try again tomorrow.",
                           {"day": day, "free_daily_budget_inr": budget,
                            "spend_today_inr": round(spend, 4)})
        cap = self.network_daily_seconds
        if network_hash and cap > 0:
            used = self.network_seconds_today(network_hash, now)
            if used + reserved_seconds + requested_seconds > cap + 1e-6:
                self.refusals[NETWORK_CAP_REASON] += 1
                log.warning("free network cap reached day=%s used_s=%.0f cap_s=%d requested_s=%.0f",
                            day, used, cap, requested_seconds)
                return Verdict(False, NETWORK_CAP_REASON,
                               "Free transcription from this network has reached today's limit. "
                               "Try again tomorrow.",
                               {"day": day, "network_daily_seconds": cap,
                                "used_seconds_today": round(used, 2)})
        return Verdict(True)

    def reserve(self, network_hash: Optional[str], seconds: float, inr: float) -> str:
        """Holds a running free job's seconds and rupees. Returns a release handle."""
        day, _, _ = self.day()
        handle = f"{day}:{secrets.token_hex(8)}"
        with self._lock:
            key = (network_hash or "", day)
            self._reserved_seconds[key] = self._reserved_seconds.get(key, 0.0) + seconds
            self._reserved_inr[handle] = inr
            self._reservation_keys[handle] = (key, seconds)
        return handle

    def release(self, handle: Optional[str]) -> None:
        if not handle:
            return
        with self._lock:
            self._reserved_inr.pop(handle, None)
            entry = self._reservation_keys.pop(handle, None)
            if entry:
                key, seconds = entry
                left = self._reserved_seconds.get(key, 0.0) - seconds
                if left > 1e-6:
                    self._reserved_seconds[key] = left
                else:
                    self._reserved_seconds.pop(key, None)

    # ---- alerts --------------------------------------------------------------
    def _alert_budget(self, spend: float, budget: float, day: str, refused: bool = False) -> None:
        if budget <= 0:
            return
        fraction = _env_float("FREE_BUDGET_ALERT_FRACTION", DEFAULT_BUDGET_ALERT_FRACTION) or \
            DEFAULT_BUDGET_ALERT_FRACTION
        if spend < budget * fraction:
            return
        if self._budget_alerted == day and not refused:
            return
        if self._budget_alerted != day:
            self._budget_alerted = day
            log.warning("FREE_BUDGET_ALERT day=%s free_spend_inr=%.2f budget_inr=%.2f"
                        " -- free-tier spend has reached %.0f%% of today's ceiling",
                        day, spend, budget, 100 * spend / budget)

    def after_free_job(self, registrations_last_hour: int = 0) -> None:
        """
        Called after a free job is metered: logs the transitions an operator can
        alert on. Never allowed to affect the response.
        """
        now = self.now()
        day, _, _ = self.day(now)
        self._alert_budget(self.spend_today_inr(now), self.daily_budget_inr, day)
        limit = _env_int("FREE_REGISTRATION_ALERT_PER_HOUR", DEFAULT_REGISTRATION_ALERT_PER_HOUR)
        if limit > 0 and registrations_last_hour >= limit and self._activity_alerted != day:
            self._activity_alerted = day
            log.warning("FREE_TIER_ACTIVITY_ALERT day=%s registrations_last_hour=%d threshold=%d"
                        " -- unusually many new installations; check for allowance resets",
                        day, registrations_last_hour, limit)
