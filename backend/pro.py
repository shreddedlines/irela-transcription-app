# -*- coding: utf-8 -*-
"""
Irela Pro usage limits, checked before any provider call.

  per billing cycle   PRO_MONTHLY_SECONDS, default 5 h. The window is the
                      subscription's own cycle (billing.usage_window): it
                      resets on renewal, not on the 1st of the month.
  per calendar day    PRO_DAILY_SECONDS, default 2 h, India time -- the same day
                      boundary as the free tier. Conservative on purpose: 2 h is
                      40 % of a cycle, enough for a long working day, while a
                      shared or scripted account cannot drain a cycle's cost in
                      one day (worst case about Rs 45 of provider cost per day).
  per rolling 24 h    PRO_TRANSCRIPTIONS_PER_DAY, default 60 jobs, and
  per minute          PRO_REQUESTS_PER_MINUTE, default 6: both in client_auth.
The global DAILY_BUDGET_INR and the kill switch apply to Pro unchanged.

Accounting is the owner guard's: successful, non-replayed jobs that reached a
provider, one per logical job however many attempts it took, with in-flight
jobs reserved so concurrent requests cannot overshoot. Every refusal carries
`resets_at`, because a billing cycle cannot be derived by the app.

A refusal refuses new work only. Nothing is deleted, here or anywhere.
"""

import datetime as _dt
import logging
import secrets
import sqlite3
import threading
import time
from contextlib import closing
from dataclasses import dataclass
from typing import Callable, Dict, Optional, Tuple

from allowance import _tz
from billing import pro_daily_seconds, pro_monthly_seconds
from free_tier import day_window

log = logging.getLogger("proxy.pro")

DAILY_REASON = "pro_daily_limit_exceeded"
MONTHLY_REASON = "pro_monthly_limit_exceeded"


@dataclass(frozen=True)
class ProDecision:
    admitted: bool
    charge_seconds: float = 0.0
    reason: Optional[str] = None
    message: str = ""
    handle: Optional[str] = None
    resets_at: Optional[str] = None


def _hours(seconds: int) -> str:
    h = seconds / 3600
    return f"{h:g} hour" + ("" if h == 1 else "s")


class ProGuard:

    _COUNTED = ("installation_id = ? AND plan = 'pro' AND success = 1 AND replayed = 0"
                " AND provider_called = 1 AND ts >= ? AND ts < ?")

    def __init__(self, path: str, clock: Callable[[], float] = time.time):
        self._path = path
        self._clock = clock
        self._lock = threading.Lock()
        self._reserved: Dict[Tuple[str, str], float] = {}
        self._handles: Dict[str, Tuple[Tuple[str, str], Tuple[str, str], float]] = {}
        self.refusals: Dict[str, int] = {DAILY_REASON: 0, MONTHLY_REASON: 0}

    @property
    def monthly_seconds(self) -> int:
        return pro_monthly_seconds()

    @property
    def daily_seconds(self) -> int:
        return pro_daily_seconds()

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
              window: Tuple[float, float], idempotency_key: Optional[str] = None) -> ProDecision:
        """
        Decide and, when admitted, reserve atomically for the cycle [window].
        The caller MUST pass the returned handle to [release] once the job's
        metering row is written.
        """
        now = self.now()
        tz = _tz()
        day, d_start, d_end = day_window(now, tz)
        c_start, c_end = window
        charge = 0.0 if self._already_counted(installation_id, idempotency_key, c_start, c_end) \
            else max(0.0, requested_seconds)
        used_day = self.used_seconds(installation_id, d_start, d_end)
        used_cycle = self.used_seconds(installation_id, c_start, c_end)
        dkey, ckey = (installation_id, "d:" + day), (installation_id, "c:%d" % int(c_start))
        with self._lock:
            r_day = self._reserved.get(dkey, 0.0)
            r_cycle = self._reserved.get(ckey, 0.0)
            if used_cycle + r_cycle + charge > self.monthly_seconds + 1e-6:
                reason = MONTHLY_REASON
            elif used_day + r_day + charge > self.daily_seconds + 1e-6:
                reason = DAILY_REASON
            else:
                reason = None
            if reason is None:
                handle = f"{day}:{secrets.token_hex(8)}"
                if charge > 0:
                    self._reserved[dkey] = r_day + charge
                    self._reserved[ckey] = r_cycle + charge
                self._handles[handle] = (dkey, ckey, charge)
        if reason is None:
            return ProDecision(True, charge, handle=handle)
        self.refusals[reason] += 1
        log.warning("pro limit reached reason=%s requested_s=%.0f", reason, requested_seconds)
        if reason == MONTHLY_REASON:
            resets = _dt.datetime.fromtimestamp(c_end, tz)
            message = (f"You have used this billing period's {_hours(self.monthly_seconds)} of "
                       f"Pro transcription. It resets on {resets.day} {resets.strftime('%B')}.")
        else:
            resets = _dt.datetime.fromtimestamp(d_end, tz)
            message = (f"You have reached today's Pro limit of {_hours(self.daily_seconds)}. "
                       f"It resets at midnight.")
        return ProDecision(False, 0.0, reason, message, resets_at=resets.isoformat())

    def release(self, handle: Optional[str]) -> None:
        if not handle:
            return
        with self._lock:
            entry = self._handles.pop(handle, None)
            if not entry:
                return
            dkey, ckey, charge = entry
            for key in (dkey, ckey):
                left = self._reserved.get(key, 0.0) - charge
                if left > 1e-6:
                    self._reserved[key] = left
                else:
                    self._reserved.pop(key, None)

    def reserved(self, installation_id: str, window: Tuple[float, float]) -> float:
        with self._lock:
            return self._reserved.get((installation_id, "c:%d" % int(window[0])), 0.0)

    def usage(self, installation_id: str, window: Tuple[float, float]) -> dict:
        """The Pro view for GET /v1/me: this cycle and today."""
        now = self.now()
        tz = _tz()
        _, d_start, d_end = day_window(now, tz)
        used = self.used_seconds(installation_id, window[0], window[1])
        reserved = self.reserved(installation_id, window)
        remaining = max(0.0, self.monthly_seconds - used - reserved)
        return {
            "cycle": {"starts_at": _dt.datetime.fromtimestamp(window[0], tz).isoformat(),
                      "resets_at": _dt.datetime.fromtimestamp(window[1], tz).isoformat(),
                      "allowance_seconds": self.monthly_seconds,
                      "used_seconds": round(used, 2),
                      "in_progress_seconds": round(reserved, 2),
                      "remaining_seconds": round(remaining, 2)},
            "day": {"used_seconds": round(self.used_seconds(installation_id, d_start, d_end), 2),
                    "allowance_seconds": self.daily_seconds,
                    "resets_at": _dt.datetime.fromtimestamp(d_end, tz).isoformat()},
        }
