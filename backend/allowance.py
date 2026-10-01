# -*- coding: utf-8 -*-
"""
Free monthly transcription allowance, enforced on the server.

Each installation may transcribe FREE_MONTHLY_SECONDS of audio (default 1200 s =
20 minutes) per calendar month. The metering database is the source of truth:
nothing is kept only in memory except the seconds of jobs that are running
right now, which are reserved so two concurrent jobs cannot both fit into the
same remaining minutes.

What counts, per installation and calendar month:
  * successful, non-replayed requests that reached a provider (one metering
    row per logical job, however many provider attempts or failovers it took);
  * each row counts its ``allowance_s`` -- the probed duration of the audio,
    or the provider-measured duration when the container could not be probed;
  * rows sharing an idempotency key count ONCE (the largest), so a retry that
    re-ran after a restart does not consume the allowance twice.

What never counts: failed jobs, rejected jobs, idempotent replays, joins of a
job already running.

A job is admitted only if its requested seconds fit entirely into what is left;
otherwise it is refused before any provider is contacted. When the duration of
an upload cannot be probed, the request is sized conservatively from its bytes
(see ``estimate_unknown_seconds``) so an unprobeable file cannot slip past.

Contains no audio, transcript, token or credential.
"""

import datetime as _dt
import os
import sqlite3
import threading
import time
from contextlib import closing
from dataclasses import dataclass
from typing import Callable, Dict, Optional, Tuple

REASON = "monthly_free_allowance_exceeded"
DEFAULT_MONTHLY_SECONDS = 1200
DEFAULT_UNKNOWN_FLOOR_BPS = 16000


def _env_int(name: str, default: int) -> int:
    try:
        return max(0, int(os.environ.get(name, default)))
    except (TypeError, ValueError):
        return default


def _tz() -> _dt.tzinfo:
    name = os.environ.get("FREE_ALLOWANCE_TIMEZONE", "UTC").strip() or "UTC"
    if name.upper() == "UTC":
        return _dt.timezone.utc
    try:
        from zoneinfo import ZoneInfo
        return ZoneInfo(name)
    except Exception:
        return _dt.timezone.utc


def month_window(now: float, tz: _dt.tzinfo) -> Tuple[str, float, float]:
    """("YYYY-MM", start, end) of the calendar month containing [now], in [tz]."""
    local = _dt.datetime.fromtimestamp(now, tz)
    start = _dt.datetime(local.year, local.month, 1, tzinfo=tz)
    end = (_dt.datetime(local.year + 1, 1, 1, tzinfo=tz) if local.month == 12
           else _dt.datetime(local.year, local.month + 1, 1, tzinfo=tz))
    return f"{local.year:04d}-{local.month:02d}", start.timestamp(), end.timestamp()


def estimate_unknown_seconds(n_bytes: int, max_seconds: float,
                             floor_bps: int = DEFAULT_UNKNOWN_FLOOR_BPS) -> float:
    """
    Upper bound on the duration of audio we could not probe: its bytes at a
    deliberately low bitrate, capped at the per-request duration limit. Errs
    toward refusing near the end of an allowance rather than overrunning it.
    """
    if n_bytes <= 0 or floor_bps <= 0:
        return 0.0
    return min(float(max_seconds), n_bytes * 8.0 / floor_bps)


@dataclass(frozen=True)
class Status:
    month: str
    allowance_seconds: int
    used_seconds: float
    in_progress_seconds: float
    remaining_seconds: float
    resets_at: str

    def as_dict(self) -> dict:
        return {"month": self.month,
                "allowance_seconds": self.allowance_seconds,
                "used_seconds": round(self.used_seconds, 2),
                "in_progress_seconds": round(self.in_progress_seconds, 2),
                "remaining_seconds": round(self.remaining_seconds, 2),
                "resets_at": self.resets_at}


@dataclass(frozen=True)
class Decision:
    admitted: bool
    charge_seconds: float        # reserved for this job; 0 when already counted
    requested_seconds: float
    status: Status


class MonthlyAllowance:
    def __init__(self, path: str, clock: Callable[[], float] = time.time):
        self._path = path
        self._clock = clock
        self._lock = threading.Lock()
        self._reserved: Dict[Tuple[str, str], float] = {}

    # ---- configuration (read per call, like the kill switch) ---------------
    @property
    def monthly_seconds(self) -> int:
        return _env_int("FREE_MONTHLY_SECONDS", DEFAULT_MONTHLY_SECONDS)

    @property
    def unknown_floor_bps(self) -> int:
        return _env_int("FREE_ALLOWANCE_UNKNOWN_FLOOR_BPS", DEFAULT_UNKNOWN_FLOOR_BPS)

    def now(self) -> float:
        return self._clock()

    def window(self, now: Optional[float] = None) -> Tuple[str, float, float]:
        return month_window(self.now() if now is None else now, _tz())

    # ---- accounting ----------------------------------------------------------
    def _connect(self):
        return sqlite3.connect(self._path, timeout=5)

    # Owner rows (owner.py) are paid for under the owner entitlement, never the
    # free allowance -- so an owner later downgraded to free is not charged for
    # them. Rows written before the plan column existed default to 'free'.
    _COUNTED = ("installation_id = ? AND ts >= ? AND ts < ? AND success = 1"
                " AND replayed = 0 AND provider_called = 1 AND plan NOT IN ('owner', 'pro')")

    def used_seconds(self, installation_id: str, now: Optional[float] = None) -> float:
        _, start, end = self.window(now)
        with closing(self._connect()) as c:
            row = c.execute(
                "SELECT COALESCE(SUM(s), 0) FROM ("
                " SELECT MAX(CASE WHEN allowance_s > 0 THEN allowance_s ELSE duration_s END) AS s"
                " FROM requests WHERE " + self._COUNTED +
                " GROUP BY COALESCE(idempotency_key, 'row:' || id))",
                (installation_id, start, end)).fetchone()
        return float(row[0] or 0.0)

    def already_counted(self, installation_id: str, idempotency_key: Optional[str],
                        now: Optional[float] = None) -> bool:
        if not idempotency_key:
            return False
        _, start, end = self.window(now)
        with closing(self._connect()) as c:
            return c.execute(
                "SELECT 1 FROM requests WHERE " + self._COUNTED +
                " AND idempotency_key = ? LIMIT 1",
                (installation_id, start, end, idempotency_key)).fetchone() is not None

    def status(self, installation_id: str, now: Optional[float] = None) -> Status:
        now = self.now() if now is None else now
        month, _, end = self.window(now)
        allowance = self.monthly_seconds
        used = self.used_seconds(installation_id, now)
        with self._lock:
            reserved = self._reserved.get((installation_id, month), 0.0)
        remaining = max(0.0, allowance - used - reserved)
        resets = _dt.datetime.fromtimestamp(end, _tz()).isoformat()
        return Status(month, allowance, used, reserved, remaining, resets)

    # ---- admission -------------------------------------------------------------
    def admit(self, installation_id: str, probed_seconds: float, n_bytes: int,
              max_seconds: float, idempotency_key: Optional[str] = None) -> Decision:
        """
        Decide and, when admitted, reserve atomically. The caller MUST call
        [release] with the returned decision once the job has finished and its
        metering row (if any) is written.
        """
        now = self.now()
        requested = probed_seconds if probed_seconds > 0 else \
            estimate_unknown_seconds(n_bytes, max_seconds, self.unknown_floor_bps)
        # A job this month's accounting already counted (a retry that re-runs
        # after the idempotency cache was lost) is not charged again.
        charge = 0.0 if self.already_counted(installation_id, idempotency_key, now) else requested
        month, _, end = self.window(now)
        allowance = self.monthly_seconds
        used = self.used_seconds(installation_id, now)
        with self._lock:
            reserved = self._reserved.get((installation_id, month), 0.0)
            fits = used + reserved + charge <= allowance + 1e-6
            if fits and charge > 0:
                self._reserved[(installation_id, month)] = reserved + charge
                reserved += charge
        remaining = max(0.0, allowance - used - reserved)
        resets = _dt.datetime.fromtimestamp(end, _tz()).isoformat()
        status = Status(month, allowance, used, reserved if fits else reserved, remaining, resets)
        return Decision(fits, charge if fits else 0.0, requested, status)

    def release(self, installation_id: str, decision: Decision) -> None:
        if not decision.admitted or decision.charge_seconds <= 0:
            return
        key = (installation_id, decision.status.month)
        with self._lock:
            left = self._reserved.get(key, 0.0) - decision.charge_seconds
            if left > 1e-6:
                self._reserved[key] = left
            else:
                self._reserved.pop(key, None)
