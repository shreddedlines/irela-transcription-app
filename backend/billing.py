# -*- coding: utf-8 -*-
"""
Irela Pro: Google Play subscriptions, verified on the server.

THE RULE THAT OVERRIDES EVERY OTHER: billing only decides how much NEW
transcription an installation may start. Expiry, cancellation, refund, payment
failure or a used-up allowance never deletes anything -- not a metering row,
not an installation, not a subscription record. Recordings and transcripts
live on the phone and are never touched by the backend at all. This module
contains no DELETE and removes no file (tests/test_billing.py enforces it).

ENTITLEMENT
  An installation is Pro while it is bound to a Play subscription whose state
  entitles it (ACTIVE, IN_GRACE_PERIOD, or CANCELED before its expiry). Owner
  outranks Pro, Pro outranks Free. The plan is decided here from Google's
  answer, never from anything the app sends.

FLOW
  app buys -> POST /v1/billing/verify {product_id, purchase_token}
    -> Google subscriptionsv2.get (server to server)
    -> checks package, product, base plan, state
    -> binds the purchase to this installation (one active installation per
       purchase; a restore on a new phone moves it, a bounded number of times)
    -> acknowledges it (Google refunds unacknowledged purchases after 3 days)
  Google -> Pub/Sub -> POST /v1/billing/rtdn  (Google-signed OIDC token)
    -> the state is re-read from Google; the notification body is never trusted
  billing_sync.py (timer) -> re-verifies, retries acknowledgements, and applies
    voided purchases (refunds, chargebacks) in case a notification was missed.

OFF BY DEFAULT: with BILLING_ENABLED unset (or anything it needs missing) both
endpoints answer 404 and no installation can become Pro through Play.

Purchase tokens are stored encrypted (Fernet, BILLING_TOKEN_KEY) and indexed by
their SHA-256. Logs carry at most a short hash prefix, never a token.
"""

import datetime as _dt
import hashlib
import logging
import os
import sqlite3
import threading
import time
from contextlib import closing
from dataclasses import dataclass
from typing import Callable, Dict, List, Optional, Tuple

from client_auth import Rejection, SlidingWindow
from play_api import PlayApi, PlayApiError, RtdnVerifier

log = logging.getLogger("proxy.billing")

# ---- Google's subscription states (subscriptionsv2) and our own two ----------
ACTIVE = "SUBSCRIPTION_STATE_ACTIVE"
IN_GRACE = "SUBSCRIPTION_STATE_IN_GRACE_PERIOD"
ON_HOLD = "SUBSCRIPTION_STATE_ON_HOLD"
PAUSED = "SUBSCRIPTION_STATE_PAUSED"
CANCELED = "SUBSCRIPTION_STATE_CANCELED"
EXPIRED = "SUBSCRIPTION_STATE_EXPIRED"
PENDING = "SUBSCRIPTION_STATE_PENDING"
PENDING_CANCELED = "SUBSCRIPTION_STATE_PENDING_PURCHASE_CANCELED"
REVOKED = "IRELA_REVOKED"          # voided: refunded or charged back
REPLACED = "IRELA_REPLACED"        # superseded by a linked purchase (upgrade, resubscribe)

ACK_PENDING = "ACKNOWLEDGEMENT_STATE_PENDING"

# Short names for clients (GET /v1/me). Unknown states read as "unknown".
PUBLIC_STATE = {ACTIVE: "active", IN_GRACE: "grace_period", ON_HOLD: "on_hold",
                PAUSED: "paused", CANCELED: "canceled", EXPIRED: "expired",
                PENDING: "pending", PENDING_CANCELED: "pending_canceled",
                REVOKED: "revoked", REPLACED: "replaced"}

# How long past its recorded expiry an ACTIVE / IN_GRACE record still entitles,
# in case a renewal notification was missed: the sync job re-reads well within
# this. Grace periods are configured at 7 days in Play Console.
ACTIVE_STALE_SLACK_S = 3 * 86400
GRACE_STALE_SLACK_S = 10 * 86400

MAX_TOKEN_CHARS = 4096
TRANSFER_WINDOW_S = 30 * 86400

# Pro defaults (overridable through the PRO_* variables in .env.example).
DEFAULT_PRO_MONTHLY_SECONDS = 5 * 3600        # 5 hours per billing cycle
DEFAULT_PRO_DAILY_SECONDS = 2 * 3600          # 2 hours per calendar day (India time)
DEFAULT_PRO_TRANSCRIPTIONS_PER_DAY = 60       # rolling 24 h
DEFAULT_PRO_REQUESTS_PER_MINUTE = 6


def _env(name: str, default: str = "") -> str:
    return os.environ.get(name, default).strip()


def _env_int(name: str, default: int) -> int:
    try:
        v = int(_env(name, str(default)))
        return v if v > 0 else default
    except ValueError:
        return default


def token_hash(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def account_id_for(installation_id: str) -> str:
    """What the app passes as obfuscatedAccountId: never the raw installation id."""
    return hashlib.sha256(("irela:" + installation_id).encode("utf-8")).hexdigest()


# ---- configuration -------------------------------------------------------------

@dataclass(frozen=True)
class BillingConfig:
    enabled_flag: bool
    package_name: str
    service_account_file: str
    token_key: str
    rtdn_audience: str
    rtdn_service_account: str
    product_id: str
    monthly_base_plan: str
    annual_base_plan: str
    transfers_per_30_days: int

    @staticmethod
    def from_env() -> "BillingConfig":
        return BillingConfig(
            enabled_flag=_env("BILLING_ENABLED").lower() in ("1", "true", "yes", "on"),
            package_name=_env("PLAY_PACKAGE_NAME"),
            service_account_file=_env("PLAY_SERVICE_ACCOUNT_FILE"),
            token_key=_env("BILLING_TOKEN_KEY"),
            rtdn_audience=_env("PLAY_RTDN_AUDIENCE"),
            rtdn_service_account=_env("PLAY_RTDN_SERVICE_ACCOUNT"),
            product_id=_env("PLAY_PRO_PRODUCT_ID", "irela_pro"),
            monthly_base_plan=_env("PLAY_PRO_MONTHLY_BASE_PLAN", "monthly"),
            annual_base_plan=_env("PLAY_PRO_ANNUAL_BASE_PLAN", "annual"),
            transfers_per_30_days=_env_int("PRO_TRANSFERS_PER_30_DAYS", 3),
        )

    @property
    def verify_enabled(self) -> bool:
        """Purchases can be verified only with everything they need configured."""
        return bool(self.enabled_flag and self.package_name and self.service_account_file
                    and self.token_key)

    @property
    def rtdn_enabled(self) -> bool:
        return bool(self.verify_enabled and self.rtdn_audience and self.rtdn_service_account)

    def months_per_period(self, base_plan: Optional[str]) -> Optional[int]:
        if base_plan == self.monthly_base_plan:
            return 1
        if base_plan == self.annual_base_plan:
            return 12
        return None


def pro_monthly_seconds() -> int:
    return _env_int("PRO_MONTHLY_SECONDS", DEFAULT_PRO_MONTHLY_SECONDS)


def pro_daily_seconds() -> int:
    return _env_int("PRO_DAILY_SECONDS", DEFAULT_PRO_DAILY_SECONDS)


# ---- token encryption ------------------------------------------------------------

class TokenCipher:
    """Fernet (AES-128-CBC + HMAC-SHA256). The key is BILLING_TOKEN_KEY."""

    def __init__(self, key: str):
        from cryptography.fernet import Fernet
        self._f = Fernet(key.encode("ascii"))

    def encrypt(self, token: str) -> str:
        return self._f.encrypt(token.encode("utf-8")).decode("ascii")

    def decrypt(self, blob: str) -> str:
        return self._f.decrypt(blob.encode("ascii")).decode("utf-8")


# ---- pure rules ------------------------------------------------------------------

def entitles(state: str, expiry_at: Optional[float], now: float) -> bool:
    """Whether a subscription in [state] gives Pro at [now]."""
    if state == ACTIVE:
        return expiry_at is None or now < expiry_at + ACTIVE_STALE_SLACK_S
    if state == IN_GRACE:
        return expiry_at is None or now < expiry_at + GRACE_STALE_SLACK_S
    if state == CANCELED:
        # Cancelled means "will not renew": paid-for time still runs to expiry.
        return expiry_at is not None and now < expiry_at
    return False


def _add_months(t: float, months: int) -> float:
    d = _dt.datetime.fromtimestamp(t, _dt.timezone.utc)
    m = d.month - 1 + months
    year, month = d.year + m // 12, m % 12 + 1
    days = [31, 29 if (year % 4 == 0 and (year % 100 != 0 or year % 400 == 0)) else 28,
            31, 30, 31, 30, 31, 31, 30, 31, 30, 31][month - 1]
    return d.replace(year=year, month=month, day=min(d.day, days)).timestamp()


def usage_window(months_per_period: int, expiry_at: float, now: float) -> Tuple[float, float]:
    """
    The month-long allowance window containing [now]. A monthly plan's window
    is its billing cycle, [expiry - 1 month, expiry); an annual plan's year is
    cut into twelve such months, so its hours reset monthly too. Outside the
    period (grace after expiry, clock skew) the nearest window is used.
    """
    start = _add_months(expiry_at, -months_per_period)
    windows = [(_add_months(start, k), _add_months(start, k + 1)) for k in range(months_per_period)]
    for w in windows:
        if w[0] <= now < w[1]:
            return w
    return windows[-1] if now >= windows[-1][0] else windows[0]


def _rfc3339(s: Optional[str]) -> Optional[float]:
    if not s:
        return None
    try:
        return _dt.datetime.fromisoformat(s.replace("Z", "+00:00")).timestamp()
    except ValueError:
        return None


def iso(t: Optional[float]) -> Optional[str]:
    return _dt.datetime.fromtimestamp(t, _dt.timezone.utc).isoformat() if t is not None else None


@dataclass(frozen=True)
class PlayFacts:
    """What we use of Google's SubscriptionPurchaseV2, validated."""
    state: str
    product_id: str
    base_plan: Optional[str]
    expiry_at: Optional[float]
    start_at: Optional[float]
    acknowledged: bool
    auto_renewing: bool
    linked_token: Optional[str]
    obfuscated_account: Optional[str]
    test: bool

    @staticmethod
    def parse(data: dict) -> "PlayFacts":
        items = data.get("lineItems") or []
        item = items[0] if items else {}
        expiries = [_rfc3339(i.get("expiryTime")) for i in items]
        expiries = [e for e in expiries if e is not None]
        return PlayFacts(
            state=str(data.get("subscriptionState") or "SUBSCRIPTION_STATE_UNSPECIFIED"),
            product_id=str(item.get("productId") or ""),
            base_plan=(item.get("offerDetails") or {}).get("basePlanId"),
            expiry_at=max(expiries) if expiries else None,
            start_at=_rfc3339(data.get("startTime")),
            acknowledged=data.get("acknowledgementState") != ACK_PENDING,
            auto_renewing=bool((item.get("autoRenewingPlan") or {}).get("autoRenewEnabled")),
            linked_token=data.get("linkedPurchaseToken") or None,
            obfuscated_account=(data.get("externalAccountIdentifiers") or {})
                .get("obfuscatedExternalAccountId"),
            test="testPurchase" in data,
        )


# ---- storage ---------------------------------------------------------------------

@dataclass(frozen=True)
class Subscription:
    token_hash: str
    source: str                       # "play" | "manual"
    product_id: str
    base_plan: Optional[str]
    state: str
    expiry_at: Optional[float]
    installation_id: Optional[str]
    acknowledged: bool
    auto_renewing: bool
    test: bool
    updated_at: float
    last_verified_at: Optional[float]
    revoked_at: Optional[float]


@dataclass(frozen=True)
class Entitlement:
    """An installation's current Pro entitlement and its allowance window."""
    subscription: Subscription
    window_start: float
    window_end: float


class SubscriptionStore:
    """Subscriptions and an append-only event log, in the metering database."""

    _SCHEMA = """
    CREATE TABLE IF NOT EXISTS subscriptions (
        token_hash        TEXT PRIMARY KEY,
        token_enc         TEXT NOT NULL DEFAULT '',
        source            TEXT NOT NULL DEFAULT 'play',
        product_id        TEXT NOT NULL,
        base_plan         TEXT,
        state             TEXT NOT NULL,
        expiry_at         REAL,
        start_at          REAL,
        installation_id   TEXT,
        account_id        TEXT,
        acknowledged      INTEGER NOT NULL DEFAULT 0,
        auto_renewing     INTEGER NOT NULL DEFAULT 0,
        test              INTEGER NOT NULL DEFAULT 0,
        created_at        REAL NOT NULL,
        updated_at        REAL NOT NULL,
        last_verified_at  REAL,
        revoked_at        REAL
    );
    CREATE INDEX IF NOT EXISTS idx_subscriptions_installation ON subscriptions(installation_id);
    CREATE TABLE IF NOT EXISTS billing_events (
        id               INTEGER PRIMARY KEY AUTOINCREMENT,
        ts               REAL NOT NULL,
        token_hash       TEXT,
        installation_id  TEXT,
        kind             TEXT NOT NULL,
        state            TEXT,
        detail           TEXT
    );
    CREATE INDEX IF NOT EXISTS idx_billing_events_token ON billing_events(token_hash, ts);
    CREATE TABLE IF NOT EXISTS billing_meta (
        key    TEXT PRIMARY KEY,
        value  TEXT NOT NULL
    );
    """

    _COLS = ("token_hash, source, product_id, base_plan, state, expiry_at, installation_id,"
             " acknowledged, auto_renewing, test, updated_at, last_verified_at, revoked_at")

    def __init__(self, path: str, clock: Callable[[], float] = time.time):
        self._path = path
        self._clock = clock
        self._lock = threading.Lock()
        with closing(sqlite3.connect(self._path, timeout=5)) as c, c:
            c.executescript(self._SCHEMA)

    def _connect(self):
        return sqlite3.connect(self._path, timeout=5)

    @staticmethod
    def _row(r) -> Subscription:
        return Subscription(r[0], r[1], r[2], r[3], r[4], r[5], r[6], bool(r[7]), bool(r[8]),
                            bool(r[9]), r[10], r[11], r[12])

    def get(self, th: str) -> Optional[Subscription]:
        with self._lock, closing(self._connect()) as c:
            r = c.execute(f"SELECT {self._COLS} FROM subscriptions WHERE token_hash = ?",
                          (th,)).fetchone()
        return self._row(r) if r else None

    def encrypted_token(self, th: str) -> Optional[str]:
        with self._lock, closing(self._connect()) as c:
            r = c.execute("SELECT token_enc FROM subscriptions WHERE token_hash = ?",
                          (th,)).fetchone()
        return r[0] if r and r[0] else None

    def for_installation(self, installation_id: str) -> List[Subscription]:
        with self._lock, closing(self._connect()) as c:
            rows = c.execute(f"SELECT {self._COLS} FROM subscriptions WHERE installation_id = ?"
                             " ORDER BY updated_at DESC", (installation_id,)).fetchall()
        return [self._row(r) for r in rows]

    def all(self) -> List[Subscription]:
        with self._lock, closing(self._connect()) as c:
            rows = c.execute(f"SELECT {self._COLS} FROM subscriptions ORDER BY updated_at DESC").fetchall()
        return [self._row(r) for r in rows]

    def upsert_facts(self, th: str, token_enc: str, facts: PlayFacts, now: float) -> None:
        """Google's current facts for a purchase. Never changes its binding."""
        with self._lock, closing(self._connect()) as c, c:
            c.execute(
                "INSERT INTO subscriptions (token_hash, token_enc, source, product_id, base_plan,"
                " state, expiry_at, start_at, account_id, acknowledged, auto_renewing, test,"
                " created_at, updated_at, last_verified_at)"
                " VALUES (?,?,'play',?,?,?,?,?,?,?,?,?,?,?,?)"
                " ON CONFLICT(token_hash) DO UPDATE SET"
                "  token_enc = CASE WHEN excluded.token_enc != '' THEN excluded.token_enc ELSE token_enc END,"
                "  product_id = excluded.product_id, base_plan = excluded.base_plan,"
                # A voided purchase stays revoked whatever Google later reports.
                "  state = CASE WHEN revoked_at IS NOT NULL THEN state ELSE excluded.state END,"
                "  expiry_at = excluded.expiry_at, start_at = excluded.start_at,"
                "  account_id = excluded.account_id,"
                "  acknowledged = MAX(acknowledged, excluded.acknowledged),"
                "  auto_renewing = excluded.auto_renewing, test = excluded.test,"
                "  updated_at = excluded.updated_at, last_verified_at = excluded.last_verified_at",
                (th, token_enc, facts.product_id, facts.base_plan, facts.state, facts.expiry_at,
                 facts.start_at, facts.obfuscated_account, int(facts.acknowledged),
                 int(facts.auto_renewing), int(facts.test), now, now, now))

    def bind(self, th: str, installation_id: str, now: float) -> None:
        with self._lock, closing(self._connect()) as c, c:
            c.execute("UPDATE subscriptions SET installation_id = ?, updated_at = ?"
                      " WHERE token_hash = ?", (installation_id, now, th))

    def set_state(self, th: str, state: str, now: float, revoked: bool = False) -> None:
        with self._lock, closing(self._connect()) as c, c:
            if revoked:
                c.execute("UPDATE subscriptions SET state = ?, revoked_at = COALESCE(revoked_at, ?),"
                          " updated_at = ? WHERE token_hash = ?", (state, now, now, th))
            else:
                c.execute("UPDATE subscriptions SET state = ?, updated_at = ? WHERE token_hash = ?"
                          " AND revoked_at IS NULL", (state, now, th))

    def mark_acknowledged(self, th: str, now: float) -> None:
        with self._lock, closing(self._connect()) as c, c:
            c.execute("UPDATE subscriptions SET acknowledged = 1, updated_at = ? WHERE token_hash = ?",
                      (now, th))

    def insert_manual(self, th: str, installation_id: str, expiry_at: float, now: float) -> None:
        """An operator grant (billing_admin.py): Pro without a Play purchase."""
        with self._lock, closing(self._connect()) as c, c:
            c.execute("INSERT INTO subscriptions (token_hash, source, product_id, base_plan, state,"
                      " expiry_at, start_at, installation_id, acknowledged, created_at, updated_at,"
                      " last_verified_at) VALUES (?,'manual','manual','monthly',?,?,?,?,1,?,?,?)",
                      (th, CANCELED, expiry_at, now, installation_id, now, now, now))

    def event(self, kind: str, th: Optional[str] = None, installation_id: Optional[str] = None,
              state: Optional[str] = None, detail: Optional[str] = None) -> None:
        with self._lock, closing(self._connect()) as c, c:
            c.execute("INSERT INTO billing_events (ts, token_hash, installation_id, kind, state, detail)"
                      " VALUES (?,?,?,?,?,?)", (self._clock(), th, installation_id, kind, state, detail))

    def transfers_since(self, th: str, since: float) -> int:
        with self._lock, closing(self._connect()) as c:
            return c.execute("SELECT COUNT(*) FROM billing_events WHERE token_hash = ?"
                             " AND kind = 'transferred' AND ts >= ?", (th, since)).fetchone()[0]

    def meta(self, key: str) -> Optional[str]:
        with self._lock, closing(self._connect()) as c:
            r = c.execute("SELECT value FROM billing_meta WHERE key = ?", (key,)).fetchone()
        return r[0] if r else None

    def set_meta(self, key: str, value: str) -> None:
        with self._lock, closing(self._connect()) as c, c:
            c.execute("INSERT INTO billing_meta (key, value) VALUES (?,?)"
                      " ON CONFLICT(key) DO UPDATE SET value = excluded.value", (key, value))

    def counts_by_state(self) -> Dict[str, int]:
        with self._lock, closing(self._connect()) as c:
            rows = c.execute("SELECT state, COUNT(*) FROM subscriptions GROUP BY state").fetchall()
        return {PUBLIC_STATE.get(s, s): n for s, n in rows}


# ---- the service -------------------------------------------------------------------

@dataclass(frozen=True)
class VerifyResult:
    plan: str                               # "pro" | "free"
    subscription: Subscription


class BillingService:
    """Decisions for /v1/billing/*, the sync job and the plan resolver."""

    def __init__(self, store: SubscriptionStore, config: BillingConfig,
                 play: Optional[PlayApi] = None, verifier: Optional[RtdnVerifier] = None,
                 cipher: Optional[TokenCipher] = None, clock: Callable[[], float] = time.time,
                 mono_clock: Callable[[], float] = time.monotonic):
        self.store = store
        self.config = config
        self._play = play
        self._verifier = verifier
        self._cipher = cipher
        self._clock = clock
        self._per_installation = SlidingWindow(3600.0, mono_clock)
        self._global = SlidingWindow(3600.0, mono_clock)
        self.outcomes: Dict[str, int] = {}

    @staticmethod
    def from_env(store: SubscriptionStore, clock: Callable[[], float] = time.time) -> "BillingService":
        """Real Google clients only when configured; otherwise billing stays off."""
        cfg = BillingConfig.from_env()
        play = verifier = cipher = None
        if cfg.verify_enabled:
            try:
                from play_api import GoogleOidcVerifier, GooglePlayApi
                cipher = TokenCipher(cfg.token_key)
                play = GooglePlayApi(cfg.package_name, cfg.service_account_file)
                if cfg.rtdn_enabled:
                    verifier = GoogleOidcVerifier(cfg.rtdn_audience, cfg.rtdn_service_account)
            except Exception:
                # A bad key or missing library must not take the service down:
                # billing simply stays off and says so in the log.
                log.exception("billing configuration invalid: billing disabled")
                play = verifier = cipher = None
        return BillingService(store, cfg, play, verifier, cipher, clock)

    @property
    def verify_enabled(self) -> bool:
        return self.config.verify_enabled and self._play is not None and self._cipher is not None

    @property
    def rtdn_enabled(self) -> bool:
        return self.verify_enabled and self.config.rtdn_enabled and self._verifier is not None

    def _count(self, outcome: str) -> None:
        self.outcomes[outcome] = self.outcomes.get(outcome, 0) + 1

    # ---- entitlement (read on every request of a free-looking installation) ---

    def entitlement(self, installation_id: str) -> Optional[Entitlement]:
        now = self._clock()
        for s in self.store.for_installation(installation_id):
            if not entitles(s.state, s.expiry_at, now):
                continue
            months = 1 if s.source == "manual" else self.config.months_per_period(s.base_plan)
            if months is None or s.expiry_at is None:
                continue
            start, end = usage_window(months, s.expiry_at, now)
            return Entitlement(s, start, end)
        return None

    def is_pro(self, installation_id: str) -> bool:
        try:
            return self.entitlement(installation_id) is not None
        except Exception:
            # Fail to Free, never to Pro: a database hiccup must not grant hours.
            log.exception("entitlement lookup failed")
            return False

    def latest_for(self, installation_id: str) -> Optional[Subscription]:
        subs = self.store.for_installation(installation_id)
        return subs[0] if subs else None

    # ---- verification -------------------------------------------------------------

    def _validate(self, facts: PlayFacts, product_id: str) -> None:
        if facts.product_id != self.config.product_id or product_id != self.config.product_id:
            raise Rejection(400, "product_not_supported", "This product is not supported.", False)
        if self.config.months_per_period(facts.base_plan) is None:
            raise Rejection(400, "product_not_supported", "This plan is not supported.", False)

    async def _fetch(self, token: str) -> PlayFacts:
        try:
            return PlayFacts.parse(await self._play.get_subscription(token))
        except PlayApiError as e:
            if e.invalid:
                raise Rejection(400, "purchase_invalid",
                                "This purchase could not be verified.", False)
            log.warning("play lookup failed status=%s", e.status)
            raise Rejection(503, "billing_unavailable",
                            "Couldn't reach Google Play. Try again shortly.", True, 30)

    async def _acknowledge(self, th: str, product_id: str, token: str) -> None:
        try:
            await self._play.acknowledge(product_id, token)
            self.store.mark_acknowledged(th, self._clock())
            self.store.event("acknowledged", th)
        except PlayApiError as e:
            # Not fatal: the sync job retries well inside Google's 3-day window.
            log.warning("acknowledge failed status=%s th=%s", e.status, th[:10])
            self.store.event("acknowledge_failed", th, detail=str(e.status))

    def _supersede(self, linked_token: Optional[str], now: float) -> None:
        if not linked_token:
            return
        old = token_hash(linked_token)
        if self.store.get(old) is not None:
            self.store.set_state(old, REPLACED, now)
            self.store.event("replaced", old, state=REPLACED)

    async def verify(self, installation_id: str, product_id: str, token: str) -> VerifyResult:
        if not self.verify_enabled:
            raise Rejection(404, "not_found", "Not found.", False)
        if not token or len(token) > MAX_TOKEN_CHARS or not isinstance(product_id, str):
            raise Rejection(400, "purchase_invalid", "This purchase could not be verified.", False)
        if not (self._per_installation.try_acquire(installation_id, 20)
                and self._global.try_acquire("all", 2000)):
            raise Rejection(429, "billing_rate_limited", "Too many attempts. Try again later.",
                            False, 3600)

        facts = await self._fetch(token)
        self._validate(facts, product_id)
        now = self._clock()
        th = token_hash(token)
        existing = self.store.get(th)
        if existing is not None and existing.installation_id not in (None, installation_id):
            # The same purchase on another installation: a restore after a
            # reinstall or a new phone. It MOVES -- one installation at a time --
            # and only a few times a month, so one purchase cannot serve a crowd.
            recent = self.store.transfers_since(th, now - TRANSFER_WINDOW_S)
            if recent >= self.config.transfers_per_30_days:
                self._count("transfer_limited")
                self.store.event("transfer_refused", th, installation_id)
                raise Rejection(409, "subscription_in_use",
                                "This subscription is active on another device. It can be moved "
                                "again later.", False)
            self.store.event("transferred", th, installation_id, detail="from:" + existing.installation_id[:8])
        self.store.upsert_facts(th, self._cipher.encrypt(token), facts, now)
        self.store.bind(th, installation_id, now)
        self._supersede(facts.linked_token, now)
        self.store.event("verified", th, installation_id, facts.state,
                         "test" if facts.test else None)
        if not facts.acknowledged and facts.state in (ACTIVE, IN_GRACE):
            await self._acknowledge(th, facts.product_id, token)
        sub = self.store.get(th)
        pro = entitles(sub.state, sub.expiry_at, now)
        self._count("verified_pro" if pro else "verified_" + PUBLIC_STATE.get(sub.state, "other"))
        log.info("purchase verified state=%s pro=%s test=%s", PUBLIC_STATE.get(sub.state, sub.state),
                 pro, facts.test)
        return VerifyResult("pro" if pro else "free", sub)

    # ---- real-time developer notifications ---------------------------------------

    async def refresh(self, token: str, event: str) -> Optional[Subscription]:
        """Re-reads one purchase from Google and stores what Google says."""
        facts = await self._fetch(token)
        now = self._clock()
        th = token_hash(token)
        self.store.upsert_facts(th, self._cipher.encrypt(token), facts, now)
        self._supersede(facts.linked_token, now)
        self.store.event(event, th, state=facts.state)
        sub = self.store.get(th)
        if not sub.acknowledged and sub.state in (ACTIVE, IN_GRACE) and facts.product_id:
            await self._acknowledge(th, facts.product_id, token)
        return self.store.get(th)

    def revoke(self, token_or_hash: str, is_hash: bool = False) -> bool:
        """A voided purchase: Free from now on. Nothing is deleted."""
        th = token_or_hash if is_hash else token_hash(token_or_hash)
        if self.store.get(th) is None:
            return False
        self.store.set_state(th, REVOKED, self._clock(), revoked=True)
        self.store.event("revoked", th, state=REVOKED)
        self._count("revoked")
        log.warning("subscription revoked th=%s", th[:10])
        return True

    async def handle_rtdn(self, authorization: Optional[str], envelope: dict) -> Tuple[int, str]:
        """(HTTP status, outcome). 2xx acknowledges the Pub/Sub message."""
        if not self.rtdn_enabled:
            return 404, "not_found"
        if not await self._verifier.verify(authorization):
            self._count("rtdn_unauthorized")
            return 401, "unauthorized"
        import base64
        import json
        try:
            data = json.loads(base64.b64decode((envelope.get("message") or {}).get("data") or ""))
        except Exception:
            self._count("rtdn_malformed")
            return 204, "malformed"                     # never retried: it cannot improve
        if data.get("packageName") != self.config.package_name:
            self._count("rtdn_other_package")
            return 204, "other_package"
        if "testNotification" in data:
            self.store.event("rtdn_test")
            return 204, "test"
        voided = data.get("voidedPurchaseNotification")
        if voided and voided.get("purchaseToken"):
            self.revoke(voided["purchaseToken"])
            return 204, "voided"
        note = data.get("subscriptionNotification")
        if not note or not note.get("purchaseToken"):
            return 204, "ignored"
        try:
            await self.refresh(note["purchaseToken"], "rtdn:%s" % note.get("notificationType"))
        except Rejection as r:
            if r.status == 503:
                return 503, "retry"                      # Pub/Sub redelivers
            return 204, r.reason
        self._count("rtdn_applied")
        return 204, "applied"

    # ---- periodic reconciliation (billing_sync.py) ---------------------------------

    async def sync(self, stale_after_s: float = 12 * 3600, voided_lookback_s: float = 30 * 86400) -> dict:
        """Re-verify live subscriptions, retry acknowledgements, apply voids."""
        if not self.verify_enabled:
            return {"enabled": False}
        now = self._clock()
        report = {"enabled": True, "refreshed": 0, "failed": 0, "revoked": 0}
        live = (ACTIVE, IN_GRACE, ON_HOLD, PAUSED, PENDING, CANCELED)
        for s in self.store.all():
            if s.source != "play" or s.revoked_at is not None or s.state not in live:
                continue
            if s.state == CANCELED and s.expiry_at is not None and s.expiry_at < now - 86400:
                continue
            if s.acknowledged and s.last_verified_at and now - s.last_verified_at < stale_after_s:
                continue
            blob = self.store.encrypted_token(s.token_hash)
            if not blob:
                continue
            try:
                await self.refresh(self._cipher.decrypt(blob), "sync")
                report["refreshed"] += 1
            except Exception:
                report["failed"] += 1
        cursor = self.store.meta("voided_cursor_ms")
        start_ms = int(cursor) if cursor else int((now - voided_lookback_s) * 1000)
        try:
            for v in await self._play.voided_purchases(start_ms):
                tok = v.get("purchaseToken")
                if tok and self.revoke(tok):
                    report["revoked"] += 1
            # Overlap by a day: a void listed late is applied, a repeat is harmless.
            self.store.set_meta("voided_cursor_ms", str(int((now - 86400) * 1000)))
        except PlayApiError as e:
            log.warning("voided purchases lookup failed status=%s", e.status)
            report["voided_error"] = e.status
        return report
