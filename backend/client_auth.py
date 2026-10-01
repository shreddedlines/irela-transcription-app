# -*- coding: utf-8 -*-
"""
Minimal client authentication and abuse protection for the beta.

THREAT MODEL (short form; full version in docs/CLOUD-AUTH-DESIGN.md)
  T1  URL discovered, requests scripted to spend provider credit
      -> every /v1/transcribe needs a backend-issued bearer token, checked
         BEFORE the request body is read.
  T2  one installation floods requests
      -> per-installation requests/minute and provider-calls/day limits,
         answered 429 retryable=false so the app never retries them.
  T3  many installations registered to multiply quota
      -> registration limited per IP and globally per hour, plus an optional
         global daily spend budget. Without platform attestation this cannot be
         prevented outright: anything the app does, a script can imitate.
  T4  a token copied from one device
      -> per-installation, revocable, excluded from backup on the device.
  T6  the token must never be, or become, a provider credential
      -> 256-bit random value generated here; only its SHA-256 is stored; it is
         never forwarded to a provider and never logged.

WHAT THIS IS NOT: user accounts, attestation, or a paid auth service.

SINGLE INSTANCE: the per-minute and per-IP counters are in process memory, like
the idempotency store. The deployment already runs exactly one replica for that
reason; the daily counters live in SQLite and survive a restart.
"""

import hashlib
import json
import os
import secrets
import sqlite3
import threading
import time
import uuid
from collections import defaultdict, deque
from contextlib import closing
from dataclasses import dataclass
from typing import Callable, Optional

TRANSCRIBE_PATH = "/v1/transcribe"
STATUS_PATH = "/v1/transcribe/status"
ALLOWANCE_PATH = "/v1/allowance"
ME_PATH = "/v1/me"
OWNER_CLAIM_PATH = "/v1/owner/claim"
BILLING_VERIFY_PATH = "/v1/billing/verify"

# Entitlement of an installation. Every installation is FREE unless it has
# redeemed the owner claim code (owner.py) or holds a verified Irela Pro
# subscription (billing.py); nothing the app sends can set it. Owner outranks Pro.
FREE_PLAN = "free"
OWNER_PLAN = "owner"
PRO_PLAN = "pro"


def _int_env(name: str, default: int) -> int:
    try:
        return max(0, int(os.environ.get(name, default)))
    except ValueError:
        return default


def _float_env(name: str) -> Optional[float]:
    raw = os.environ.get(name, "").strip()
    if not raw:
        return None
    try:
        v = float(raw)
        return v if v > 0 else None
    except ValueError:
        return None


@dataclass(frozen=True)
class Limits:
    """Read at startup. Every value is an environment variable."""
    requests_per_minute: int
    transcriptions_per_day: int
    registrations_per_ip_per_hour: int
    registrations_per_hour: int
    daily_budget_inr: Optional[float]
    # Owner installations replace the two per-installation limits above with
    # their own. The global daily budget applies to owners unchanged.
    owner_requests_per_minute: int = 6
    owner_transcriptions_per_day: int = 150
    # Pro installations likewise (billing.py documents the defaults).
    pro_requests_per_minute: int = 6
    pro_transcriptions_per_day: int = 60

    @staticmethod
    def from_env() -> "Limits":
        return Limits(
            requests_per_minute=_int_env("CLIENT_REQUESTS_PER_MINUTE", 6),
            transcriptions_per_day=_int_env("CLIENT_TRANSCRIPTIONS_PER_DAY", 40),
            registrations_per_ip_per_hour=_int_env("REGISTRATIONS_PER_IP_PER_HOUR", 10),
            registrations_per_hour=_int_env("REGISTRATIONS_PER_HOUR", 200),
            daily_budget_inr=_float_env("DAILY_BUDGET_INR"),
            owner_requests_per_minute=_int_env("OWNER_REQUESTS_PER_MINUTE", 6),
            owner_transcriptions_per_day=_int_env("OWNER_TRANSCRIPTIONS_PER_DAY", 150),
            pro_requests_per_minute=_int_env("PRO_REQUESTS_PER_MINUTE", 6),
            pro_transcriptions_per_day=_int_env("PRO_TRANSCRIPTIONS_PER_DAY", 60),
        )

    def per_minute(self, plan: str) -> int:
        if plan == OWNER_PLAN:
            return self.owner_requests_per_minute
        return self.pro_requests_per_minute if plan == PRO_PLAN else self.requests_per_minute

    def per_day(self, plan: str) -> int:
        if plan == OWNER_PLAN:
            return self.owner_transcriptions_per_day
        return self.pro_transcriptions_per_day if plan == PRO_PLAN else self.transcriptions_per_day


def token_hash(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


class SlidingWindow:
    """In-memory event counter per key over a fixed window."""

    def __init__(self, window_s: float, clock: Callable[[], float] = time.monotonic):
        self._window = window_s
        self._clock = clock
        self._events = defaultdict(deque)
        self._lock = threading.Lock()

    def try_acquire(self, key: str, limit: int) -> bool:
        if limit <= 0:
            return False
        now = self._clock()
        with self._lock:
            q = self._events[key]
            while q and now - q[0] >= self._window:
                q.popleft()
            if len(q) >= limit:
                return False
            q.append(now)
            return True


class InstallationStore:
    """Installations, in the same SQLite file as metering (one volume)."""

    _SCHEMA = """
    CREATE TABLE IF NOT EXISTS installations (
        id          TEXT PRIMARY KEY,
        token_hash  TEXT NOT NULL UNIQUE,
        created_at  REAL NOT NULL,
        revoked     INTEGER NOT NULL DEFAULT 0
    );
    CREATE INDEX IF NOT EXISTS idx_installations_created ON installations(created_at);
    """

    def __init__(self, path: str):
        self._path = path
        self._lock = threading.Lock()
        with closing(sqlite3.connect(self._path, timeout=5)) as c, c:
            c.executescript(self._SCHEMA)
            # Entitlements live in their own table so `installations` keeps
            # exactly its four columns: nothing about an installation is stored
            # beyond an id, a token hash, a timestamp and a revoked flag. An
            # installation with no row here is FREE -- which is every existing
            # one, so this adds nothing to migrate.
            c.executescript(self._PLANS_SCHEMA)

    _PLANS_SCHEMA = """
    CREATE TABLE IF NOT EXISTS installation_plans (
        installation_id TEXT PRIMARY KEY REFERENCES installations(id),
        plan            TEXT NOT NULL,
        since           REAL NOT NULL
    );
    """

    def _connect(self):
        return sqlite3.connect(self._path, timeout=5)

    def create(self, now: float) -> tuple:
        token = secrets.token_urlsafe(32)          # 256 bits
        installation_id = str(uuid.uuid4())
        with self._lock, closing(self._connect()) as c, c:
            c.execute("INSERT INTO installations (id, token_hash, created_at) VALUES (?,?,?)",
                      (installation_id, token_hash(token), now))
        return installation_id, token

    def lookup(self, token: str) -> Optional[tuple]:
        """(installation_id, revoked) for a presented token, or None."""
        found = self.lookup_entitlement(token)
        return (found[0], found[1]) if found else None

    _WITH_PLAN = ("SELECT i.id, i.revoked, COALESCE(p.plan, 'free'), p.since, i.created_at"
                  " FROM installations i LEFT JOIN installation_plans p"
                  " ON p.installation_id = i.id")

    def lookup_entitlement(self, token: str) -> Optional[tuple]:
        """(installation_id, revoked, plan) for a presented token, or None."""
        with self._lock, closing(self._connect()) as c:
            row = c.execute(self._WITH_PLAN + " WHERE i.token_hash = ?",
                            (token_hash(token),)).fetchone()
        return (row[0], bool(row[1]), row[2]) if row else None

    def plan_of(self, installation_id: str) -> Optional[str]:
        """The installation's plan, or None if there is no such installation."""
        with self._lock, closing(self._connect()) as c:
            row = c.execute(self._WITH_PLAN + " WHERE i.id = ?", (installation_id,)).fetchone()
        return row[2] if row else None

    def set_plan(self, installation_id: str, plan: str, now: float) -> None:
        with self._lock, closing(self._connect()) as c, c:
            c.execute("INSERT INTO installation_plans (installation_id, plan, since)"
                      " VALUES (?,?,?) ON CONFLICT(installation_id)"
                      " DO UPDATE SET plan = excluded.plan, since = excluded.since",
                      (installation_id, plan, now))

    def active_owner_count(self) -> int:
        """Owner installations that are not revoked."""
        with self._lock, closing(self._connect()) as c:
            return c.execute(
                "SELECT COUNT(*) FROM installation_plans p JOIN installations i"
                " ON i.id = p.installation_id WHERE p.plan = ? AND i.revoked = 0",
                (OWNER_PLAN,)).fetchone()[0]

    def list_installations(self, plan: Optional[str] = None) -> list:
        """(id, revoked, plan, plan_since, created_at) rows, for the admin script."""
        sql, args = self._WITH_PLAN, ()
        if plan:
            sql += " WHERE COALESCE(p.plan, 'free') = ?"
            args = (plan,)
        with self._lock, closing(self._connect()) as c:
            return c.execute(sql + " ORDER BY i.created_at", args).fetchall()

    def created_since(self, since: float) -> int:
        with self._lock, closing(self._connect()) as c:
            return c.execute("SELECT COUNT(*) FROM installations WHERE created_at >= ?",
                             (since,)).fetchone()[0]

    def revoke(self, installation_id: str) -> None:
        with self._lock, closing(self._connect()) as c, c:
            c.execute("UPDATE installations SET revoked = 1 WHERE id = ?", (installation_id,))


class Rejection(Exception):
    def __init__(self, status: int, reason: str, message: str, retryable: bool,
                 retry_after: Optional[int] = None):
        super().__init__(message)
        self.status, self.reason, self.message = status, reason, message
        self.retryable, self.retry_after = retryable, retry_after

    def body(self) -> bytes:
        return json.dumps({"error": self.message, "retryable": self.retryable,
                           "reason": self.reason}).encode("utf-8")


class ClientAuth:
    """The decisions, separate from HTTP so they are testable directly."""

    def __init__(self, store: InstallationStore, limits: Limits,
                 provider_calls_since: Callable[[str, float], int],
                 spend_since: Callable[[float], float],
                 is_cached_replay: Callable[[str, str], bool] = lambda i, k: False,
                 wall_clock: Callable[[], float] = time.time,
                 mono_clock: Callable[[], float] = time.monotonic,
                 pro_resolver: Optional[Callable[[str], bool]] = None):
        self.store = store
        # Whether a FREE installation currently holds Pro (billing.py). Asked
        # only for installations with no owner entitlement; never raises Pro
        # on an error (the resolver fails to False).
        self._pro_resolver = pro_resolver
        self.limits = limits
        self._provider_calls_since = provider_calls_since
        self._spend_since = spend_since
        self._is_cached_replay = is_cached_replay
        self._now = wall_clock
        self._per_minute = SlidingWindow(60.0, mono_clock)
        self._per_ip = SlidingWindow(3600.0, mono_clock)
        self.rejections = defaultdict(int)          # counts only, for /v1/usage

    def _resolve(self, installation_id: str, plan: str) -> str:
        if plan == FREE_PLAN and self._pro_resolver is not None \
                and self._pro_resolver(installation_id):
            return PRO_PLAN
        return plan

    def _reject(self, r: Rejection) -> Rejection:
        self.rejections[r.reason] += 1
        return r

    # ---- registration ------------------------------------------------------

    def register(self, client_ip: str) -> tuple:
        if not self._per_ip.try_acquire(client_ip or "unknown",
                                        self.limits.registrations_per_ip_per_hour):
            raise self._reject(Rejection(429, "registration_limited",
                "Too many new installations from this network. Try again later.", False, 3600))
        now = self._now()
        if self.store.created_since(now - 3600) >= self.limits.registrations_per_hour:
            raise self._reject(Rejection(429, "registration_limited",
                "Transcription sign-up is busy. Try again later.", False, 3600))
        return self.store.create(now)

    # ---- per request, before the body --------------------------------------

    def identify(self, authorization: Optional[str]) -> str:
        """
        Token check only, for requests that can never reach a provider (status
        polls). Rate limits and quota are deliberately not applied: a poll costs
        nothing and must not be refused while paid work is still running.
        """
        return self.identify_with_plan(authorization)[0]

    def identify_with_plan(self, authorization: Optional[str]) -> tuple:
        """(installation_id, plan) after a token-only check, or raises Rejection."""
        scheme, _, token = (authorization or "").partition(" ")
        found = self.store.lookup_entitlement(token.strip()) \
            if scheme.lower() == "bearer" and token.strip() else None
        if found is None:
            raise self._reject(Rejection(401, "unauthorized",
                "This app needs to register again.", True))
        installation_id, revoked, plan = found
        if revoked:
            raise self._reject(Rejection(403, "revoked",
                "Transcription is not available for this installation.", False))
        return installation_id, self._resolve(installation_id, plan)

    def authorize(self, authorization: Optional[str],
                  idempotency_key: Optional[str] = None) -> str:
        """The installation id, or raises Rejection. Never reads the body."""
        return self.admit(authorization, idempotency_key)[0]

    def admit(self, authorization: Optional[str],
              idempotency_key: Optional[str] = None) -> tuple:
        """(installation_id, plan), or raises Rejection. Never reads the body."""
        scheme, _, token = (authorization or "").partition(" ")
        if scheme.lower() != "bearer" or not token.strip():
            raise self._reject(Rejection(401, "unauthorized",
                "This app needs to register again.", True))
        found = self.store.lookup_entitlement(token.strip())
        if found is None:
            # retryable=true: the app discards the token and re-registers on
            # its next (bounded) attempt -- e.g. after a database loss.
            raise self._reject(Rejection(401, "unauthorized",
                "This app needs to register again.", True))
        installation_id, revoked, plan = found
        if revoked:
            raise self._reject(Rejection(403, "revoked",
                "Transcription is not available for this installation.", False))
        plan = self._resolve(installation_id, plan)

        # A retry of a job this installation already completed is served from
        # the idempotency cache at no provider cost. It must not be refused by
        # quota: the response may simply have been lost on a bad network, and
        # refusing it would throw away a transcript that was already paid for.
        if idempotency_key and self._is_cached_replay(installation_id, idempotency_key):
            return installation_id, plan

        # Owners get their own per-installation limits (150/day by default);
        # everything below them -- the global budget included -- is shared.
        if not self._per_minute.try_acquire(installation_id, self.limits.per_minute(plan)):
            raise self._reject(Rejection(429, "rate_limited",
                "Too many transcriptions in a short time. Try again in a minute.", False, 60))

        now = self._now()
        if self._provider_calls_since(installation_id, now - 86400) >= self.limits.per_day(plan):
            raise self._reject(Rejection(429, "quota",
                "You have reached today's transcription limit. Try again tomorrow.", False, 3600))

        budget = self.limits.daily_budget_inr
        if budget is not None and self._spend_since(now - 86400) >= budget:
            raise self._reject(Rejection(503, "budget",
                "Transcription is temporarily unavailable.", False))
        return installation_id, plan

    def budget_exhausted(self) -> bool:
        """True when the global daily budget would refuse the next job."""
        budget = self.limits.daily_budget_inr
        return budget is not None and self._spend_since(self._now() - 86400) >= budget


class AuthGate:
    """
    Pure ASGI middleware for POST /v1/transcribe.

    Runs before FastAPI parses the multipart form, so an unauthorized, revoked,
    rate-limited or over-quota request is answered without the audio ever
    being read -- and certainly without a provider call. It writes nothing to
    the database for a rejection, so a flood cannot amplify into disk load.
    """

    def __init__(self, app, auth_provider: Callable[[], ClientAuth]):
        self.app = app
        self._auth = auth_provider

    async def __call__(self, scope, receive, send):
        path, method = scope.get("path"), scope.get("method")
        is_transcribe = path == TRANSCRIBE_PATH and method == "POST"
        # Status polls, the allowance and account views, the owner claim and
        # purchase verification never reach a provider: token only. (The claim
        # and verification have their own limits.) Google's push notifications
        # (/v1/billing/rtdn) carry no installation token and are not gated here.
        is_status = (method == "GET" and path in (STATUS_PATH, ALLOWANCE_PATH, ME_PATH)) or \
            (method == "POST" and path in (OWNER_CLAIM_PATH, BILLING_VERIFY_PATH))
        if scope["type"] != "http" or not (is_transcribe or is_status):
            await self.app(scope, receive, send)
            return
        headers = {k.decode("latin-1").lower(): v.decode("latin-1")
                   for k, v in scope.get("headers", [])}
        try:
            auth = self._auth()
            authorization = headers.get("authorization")
            # Plan-aware when the provider supports it; an auth object with only
            # the original identify/authorize interface still works, as FREE.
            if is_status:
                if hasattr(auth, "identify_with_plan"):
                    installation_id, plan = auth.identify_with_plan(authorization)
                else:
                    installation_id, plan = auth.identify(authorization), FREE_PLAN
            elif hasattr(auth, "admit"):
                installation_id, plan = auth.admit(authorization, headers.get("idempotency-key"))
            else:
                installation_id = auth.authorize(authorization, headers.get("idempotency-key"))
                plan = FREE_PLAN
        except Rejection as r:
            extra = [(b"retry-after", str(r.retry_after).encode())] if r.retry_after else []
            if r.status == 401:
                extra.append((b"www-authenticate", b"Bearer"))
            await send({"type": "http.response.start", "status": r.status,
                        "headers": [(b"content-type", b"application/json")] + extra})
            await send({"type": "http.response.body", "body": r.body()})
            return
        state = scope.setdefault("state", {})
        state["installation_id"] = installation_id
        state["plan"] = plan
        await self.app(scope, receive, send)
