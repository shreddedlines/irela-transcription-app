# -*- coding: utf-8 -*-
"""
Persistent request accounting, so provider spend is observable and boundable.

SQLite via the standard library: durable across restarts, no service to pay
for, and adequate for a single instance. A multi-instance deployment should
point this at the same shared database it uses for idempotency.

NEVER STORES TRANSCRIPT TEXT. Only counts, durations, status and estimated
cost. `chars` is a length, deliberately not the content -- the same rule the
Android side follows.
"""

import os
import sqlite3
import threading
import time
from contextlib import closing
from typing import Optional

def default_db_path() -> str:
    """
    Resolved per construction, not once at import.

    Reading the environment into a module constant meant METERING_DB was
    captured whenever this module first happened to be imported, so setting it
    later -- in a test, or in a deployment that configures the environment after
    import -- was silently ignored and rows went to the wrong file.
    """
    return os.environ.get("METERING_DB", os.path.join(
        os.path.dirname(os.path.abspath(__file__)), "metering.sqlite3"))

_SCHEMA = """
CREATE TABLE IF NOT EXISTS requests (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    ts              REAL    NOT NULL,
    provider        TEXT    NOT NULL,
    bytes           INTEGER NOT NULL,
    duration_s      REAL    NOT NULL,
    success         INTEGER NOT NULL,
    status          INTEGER NOT NULL,
    replayed        INTEGER NOT NULL DEFAULT 0,
    est_cost_inr    REAL    NOT NULL DEFAULT 0,
    chars           INTEGER NOT NULL DEFAULT 0,
    idempotency_key TEXT
);
CREATE INDEX IF NOT EXISTS idx_requests_ts ON requests(ts);
"""


class Metering:
    def __init__(self, path: Optional[str] = None):
        self._path = path or default_db_path()
        self._lock = threading.Lock()
        with closing(self._connect()) as c, c:
            c.executescript(_SCHEMA)
            # Additive migration for databases created before client auth:
            # which installation asked, and whether a provider was contacted
            # (the daily quota counts only those).
            cols = {r[1] for r in c.execute("PRAGMA table_info(requests)")}
            if "installation_id" not in cols:
                c.execute("ALTER TABLE requests ADD COLUMN installation_id TEXT")
            if "provider_called" not in cols:
                c.execute("ALTER TABLE requests ADD COLUMN provider_called "
                          "INTEGER NOT NULL DEFAULT 0")
            # Provider router: how many provider calls one request took, and
            # whether it was served by the backup.
            if "attempts" not in cols:
                c.execute("ALTER TABLE requests ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0")
            if "failed_over" not in cols:
                c.execute("ALTER TABLE requests ADD COLUMN failed_over INTEGER NOT NULL DEFAULT 0")
            # Free monthly allowance: seconds this row counts against its
            # installation's allowance (see allowance.py). 0 on older rows,
            # which then count their duration_s.
            if "allowance_s" not in cols:
                c.execute("ALTER TABLE requests ADD COLUMN allowance_s REAL NOT NULL DEFAULT 0")
            # Free-tier abuse protection (free_tier.py): whether this row was
            # served under the free allowance, and an opaque per-day handle for
            # the client network -- a salted hash, never an address. Older rows
            # default to 0/NULL and are simply not counted by those limits.
            if "free_tier" not in cols:
                c.execute("ALTER TABLE requests ADD COLUMN free_tier INTEGER NOT NULL DEFAULT 0")
            if "network_hash" not in cols:
                c.execute("ALTER TABLE requests ADD COLUMN network_hash TEXT")
            # Owner entitlement (owner.py): which plan the installation was on
            # when the row was written. Older rows default to 'free', so every
            # existing count is unchanged.
            if "plan" not in cols:
                c.execute("ALTER TABLE requests ADD COLUMN plan TEXT NOT NULL DEFAULT 'free'")
            c.execute("CREATE INDEX IF NOT EXISTS idx_requests_free_day"
                      " ON requests(free_tier, ts)")
            c.execute("CREATE INDEX IF NOT EXISTS idx_requests_install_ts "
                      "ON requests(installation_id, ts)")

    @property
    def path(self) -> str:
        return self._path

    def _connect(self):
        return sqlite3.connect(self._path, timeout=5)

    def record(self, provider: str, bytes_: int, duration_s: float,
               success: bool, status: int, est_cost_inr: float = 0.0,
               chars: int = 0, replayed: bool = False,
               idempotency_key: Optional[str] = None,
               installation_id: Optional[str] = None,
               provider_called: bool = False, attempts: int = 0,
               failed_over: bool = False, allowance_s: float = 0.0,
               free_tier: bool = False, network_hash: Optional[str] = None,
               plan: str = "free") -> None:
        with self._lock, closing(self._connect()) as c, c:
            c.execute(
                "INSERT INTO requests (ts, provider, bytes, duration_s, success,"
                " status, replayed, est_cost_inr, chars, idempotency_key,"
                " installation_id, provider_called, attempts, failed_over, allowance_s,"
                " free_tier, network_hash, plan)"
                " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                (time.time(), provider, int(bytes_), float(duration_s),
                 1 if success else 0, int(status), 1 if replayed else 0,
                 float(est_cost_inr), int(chars), idempotency_key,
                 installation_id, 1 if provider_called else 0, int(attempts),
                 1 if failed_over else 0, float(allowance_s),
                 1 if free_tier else 0, network_hash, plan or "free"))

    def provider_calls_since(self, installation_id: str, since: float) -> int:
        """Requests from one installation that reached a provider."""
        with self._lock, closing(self._connect()) as c:
            return c.execute(
                "SELECT COUNT(*) FROM requests WHERE installation_id = ?"
                " AND provider_called = 1 AND ts >= ?",
                (installation_id, since)).fetchone()[0]

    def spend_since(self, since: float) -> float:
        with self._lock, closing(self._connect()) as c:
            return float(c.execute(
                "SELECT COALESCE(SUM(est_cost_inr),0) FROM requests WHERE ts >= ?",
                (since,)).fetchone()[0])

    def totals(self, since: float = 0.0) -> dict:
        with self._lock, closing(self._connect()) as c:
            row = c.execute(
                "SELECT COUNT(*), COALESCE(SUM(duration_s),0),"
                " COALESCE(SUM(est_cost_inr),0),"
                " COALESCE(SUM(success),0), COALESCE(SUM(replayed),0)"
                " FROM requests WHERE ts >= ?", (since,)).fetchone()
            by_provider = dict(c.execute(
                "SELECT provider, COUNT(*) FROM requests WHERE ts >= ?"
                " GROUP BY provider", (since,)).fetchall())
        return {
            "requests": row[0],
            "audio_seconds": round(row[1], 2),
            "est_cost_inr": round(row[2], 4),
            "successes": row[3],
            "failures": row[0] - row[3],
            "replayed": row[4],
            "by_provider": by_provider,
        }
