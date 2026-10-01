# -*- coding: utf-8 -*-
"""
Minimal transcription proxy.

Exists for one non-negotiable reason: **a provider API key cannot ship inside
an APK.** Anything in an installed app is extractable, so the keys live here and
the app never contacts a provider directly.

Everything else here is cost containment and reliability, and the ordering
matters. Nothing reaches a provider until it has passed, in this order:

    client auth -> per-client rate/quota -> budget     (middleware, body unread)
    -> kill switch -> byte cap -> duration cap
    -> idempotency replay -> in-flight join
    -> FREE: free-tier network cap + free budget (free_tier.py)
             -> free monthly allowance (allowance.py)
       OWNER: owner daily/monthly ceilings (owner.py)
       PRO:   Pro billing-cycle and daily ceilings (pro.py, billing.py)
    -> provider router

so the cheapest rejection happens first and a provider call is the last resort.
The router (provider_router.py) prefers Deepgram and fails over to AssemblyAI;
which provider served a request is decided here, never by the app, so a
provider outage never needs an APK update.

    pip install -r requirements.txt
    DEEPGRAM_API_KEY=... ASSEMBLYAI_API_KEY=... uvicorn app:app --port 8080

Run behind TLS. It accepts raw audio and must never be exposed plaintext.
"""

import asyncio
import contextlib
import hmac
import json
import logging
import os
import time
from typing import Dict, List, Optional

from fastapi import FastAPI, File, Form, Header, HTTPException, Request, UploadFile
from fastapi.responses import JSONResponse, Response

import client_auth
from allowance import (REASON as ALLOWANCE_REASON, MonthlyAllowance,
                       estimate_unknown_seconds)
from free_tier import FreeTierGuard
from owner import OwnerClaim, OwnerGuard
from billing import BillingService, SubscriptionStore
from pro import ProGuard

import limits as audio_limits
from idempotency import InProcessIdempotencyStore
from metering import Metering
from provider_health import ProviderHealth
from provider_router import (PREFERENCE, AllProvidersUnavailable, InputRejected,
                             ProviderRouter)
from usage_alert import UsageAlert
from providers import assemblyai, deepgram

# ---------------------------------------------------------------------------
# Logging: transcripts are never logged, in any environment. Byte counts,
# durations, provider and status only. Same rule as the Android side.
# ---------------------------------------------------------------------------
logging.basicConfig(level=logging.INFO,
                    format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("proxy")

# httpx logs every request at INFO with the FULL URL. The Deepgram call carries
# keyterms in the query string (people's names, by design), so at INFO they
# landed in the server log. Our own lines already record
# provider, bytes, status and duration; nothing is lost by raising these.
for _noisy in ("httpx", "httpcore"):
    logging.getLogger(_noisy).setLevel(logging.WARNING)

app = FastAPI(title="apex transcription proxy", version="0.3.0")

PROVIDERS = {"deepgram": deepgram, "assemblyai": assemblyai}

# Accepted values of the client's `provider` field. It is a hint from older
# builds and is IGNORED for routing: the backend alone decides.
ACCEPTED_PROVIDER_HINTS = set(PROVIDERS) | {"auto"}

# Swap for a shared implementation before running more than one instance --
# see idempotency.py. Left in-process deliberately: no Redis, no paid service.
idempotency = InProcessIdempotencyStore()
meter = Metering()

# Persisted next to metering: a restart during an outage keeps routing around
# the broken provider.
health = ProviderHealth(meter.path)
router = ProviderRouter(PROVIDERS, health)

# Requests whose provider work is still running, by scoped idempotency key. A
# client that timed out and retried joins the running work instead of paying
# for it twice. In-process, like the idempotency store (single instance only).
inflight: Dict[str, "asyncio.Task"] = {}

# Terminal FAILURE outcomes of finished work, briefly, by scoped key -- so a
# status poll learns "all providers unavailable" or "audio rejected" instead of
# "unknown job". Status, reason and message only; never content.
RECENT_FAILURE_TTL_S = 600.0
recent_failures: Dict[str, tuple] = {}


def _remember_failure(scoped_key: Optional[str], response: JSONResponse) -> None:
    if not scoped_key:
        return
    now = time.time()
    for k in [k for k, v in recent_failures.items() if now - v[0] > RECENT_FAILURE_TTL_S]:
        recent_failures.pop(k, None)
    retry_after = response.headers.get("retry-after")
    recent_failures[scoped_key] = (now, response.status_code, bytes(response.body), retry_after)


def _recent_failure(scoped_key: str) -> Optional[JSONResponse]:
    hit = recent_failures.get(scoped_key)
    if not hit or time.time() - hit[0] > RECENT_FAILURE_TTL_S:
        return None
    _, status, body, retry_after = hit
    return JSONResponse(json.loads(body), status_code=status,
                        headers={"Retry-After": retry_after} if retry_after else None)

# Spend threshold. In-process state like the idempotency store, but unlike it
# the failure mode of replication is a duplicated log line, not a double
# charge -- see usage_alert.py.
usage_alert = UsageAlert()


def _env_float(name: str, default: float) -> float:
    try:
        return float(os.environ.get(name, default))
    except (TypeError, ValueError):
        return default


# Longest one HTTP request waits for provider work before telling the client to
# come back. Below the app's 120 s read timeout, so the client hears a
# deliberate "still processing" instead of timing out.
def handler_wait_s() -> float:
    return _env_float("HANDLER_WAIT_S", 100.0)


# Irela Pro subscriptions (billing.py). Off unless BILLING_ENABLED and all of
# its configuration are set: then no installation can become Pro through Play
# and the billing endpoints answer 404. Built before `auth`, which asks it
# whether a free installation currently holds Pro.
subscriptions = SubscriptionStore(meter.path)
billing = BillingService.from_env(subscriptions)

# Client authentication and abuse limits. Installations live in the metering
# database so a single persistent volume holds all state. See client_auth.py.
auth = client_auth.ClientAuth(
    store=client_auth.InstallationStore(meter.path),
    limits=client_auth.Limits.from_env(),
    provider_calls_since=meter.provider_calls_since,
    spend_since=meter.spend_since,
    # A replay of a finished job, or a retry joining one still running, is
    # served without a new provider call and must not be refused on quota.
    is_cached_replay=lambda installation_id, key:
        idempotency.get(f"{installation_id}:{key}") is not None
        or f"{installation_id}:{key}" in inflight,
    pro_resolver=billing.is_pro,
)
app.add_middleware(client_auth.AuthGate, auth_provider=lambda: auth)

# Free transcription allowance per installation per calendar month, counted
# from the metering database. See allowance.py.
allowance = MonthlyAllowance(meter.path)

# Free-tier abuse protection: a daily cap per client network and a daily free
# spend ceiling, both separate from DAILY_BUDGET_INR. See free_tier.py.
free_tier = FreeTierGuard(meter.path)

# Owner entitlement: the claim code, and the owner's own safety ceilings in
# place of the free tier. Claiming is disabled until OWNER_CLAIM_CODE_SHA256 is
# set, so with no configuration every installation is free. See owner.py.
owner_claim = OwnerClaim(auth.store)
owner_guard = OwnerGuard(meter.path)

# Pro's own ceilings in place of the free tier. See pro.py.
pro_guard = ProGuard(meter.path)


def transcription_enabled() -> bool:
    """
    Global kill switch. Read per request, not cached, so flipping the
    environment variable and restarting is not required to stop spending --
    any process manager that re-execs picks it up immediately, and a hot
    reload picks it up on the next call.
    """
    return os.environ.get("TRANSCRIPTION_ENABLED", "true").strip().lower() \
        not in ("false", "0", "no", "off")


@app.get("/healthz")
async def healthz():
    return {
        "ok": True,
        "transcription_enabled": transcription_enabled(),
        "idempotency_shared": idempotency.is_shared,
        "limits": {
            "version": audio_limits.VERSION,
            "max_duration_seconds": audio_limits.MAX_DURATION_SECONDS,
            "max_upload_bytes": audio_limits.MAX_UPLOAD_BYTES,
        },
        "providers": {
            name: bool(os.environ.get(f"{name.upper()}_API_KEY", "").strip())
            for name in PROVIDERS
        },
        "provider_preference": list(PREFERENCE),
        "provider_health": health.snapshot(PROVIDERS),
        "client_auth": {
            "required": True,
            "requests_per_minute": auth.limits.requests_per_minute,
            "transcriptions_per_day": auth.limits.transcriptions_per_day,
            "daily_budget_inr": auth.limits.daily_budget_inr,
        },
    }


@app.post("/v1/installations", status_code=201)
async def register_installation(request: Request):
    """
    Issues a per-installation bearer token. Takes no body and records no
    device identifier; the client address is used only for an in-memory
    per-hour counter and is never stored.
    """
    ip = request.client.host if request.client else "unknown"
    try:
        installation_id, token = auth.register(ip)
    except client_auth.Rejection as r:
        headers = {"Retry-After": str(r.retry_after)} if r.retry_after else None
        return JSONResponse({"error": r.message, "retryable": r.retryable,
                             "reason": r.reason}, status_code=r.status, headers=headers)
    log.info("installation registered")          # no id, no token, no address
    return {"installation_id": installation_id, "token": token}


def _admin_refusal(request: Request) -> Optional[JSONResponse]:
    """
    None when the caller may read operator data. With ADMIN_TOKEN unset the
    route is open, as before; once it is set, the exact bearer token is required
    (constant-time compare). The token is never logged or echoed.
    """
    expected = os.environ.get("ADMIN_TOKEN", "").strip()
    if not expected:
        return None
    scheme, _, presented = (request.headers.get("authorization") or "").partition(" ")
    if scheme.lower() == "bearer" and presented.strip() and hmac.compare_digest(
            presented.strip().encode("utf-8"), expected.encode("utf-8")):
        return None
    return JSONResponse({"error": "Not authorized.", "retryable": False,
                         "reason": "admin_unauthorized"},
                        status_code=401, headers={"WWW-Authenticate": "Bearer"})


@app.get("/v1/usage")
async def usage(request: Request, since_hours: float = 24.0):
    """Operational view of spend. Counts and costs only, never content."""
    refused = _admin_refusal(request)
    if refused is not None:
        return refused
    totals = meter.totals(since=time.time() - since_hours * 3600)
    # The threshold is reported against the ALERT window, which is configured
    # independently of whatever window the caller asked to see -- otherwise
    # `?since_hours=1` would quietly report "not triggered" for a breach that
    # happened two hours ago.
    alert_window = usage_alert_window_totals()
    totals.update(usage_alert.status(alert_window.get("est_cost_inr", 0.0)))
    totals["alert_window_est_cost_inr"] = alert_window.get("est_cost_inr", 0.0)
    totals["auth_rejections"] = dict(auth.rejections)
    totals["provider_health"] = health.snapshot(PROVIDERS)
    totals["free_tier"] = {**free_tier.stats(),
                           "registrations_last_hour": auth.store.created_since(time.time() - 3600)}
    totals["owner"] = {"claim_enabled": owner_claim.enabled,
                       "active_installations": auth.store.active_owner_count(),
                       "max_installations": owner_claim.max_installations,
                       "claims": dict(owner_claim.outcomes),
                       "refusals": dict(owner_guard.refusals)}
    totals["billing"] = {"verify_enabled": billing.verify_enabled,
                         "rtdn_enabled": billing.rtdn_enabled,
                         "subscriptions": subscriptions.counts_by_state(),
                         "outcomes": dict(billing.outcomes),
                         "pro_refusals": dict(pro_guard.refusals)}
    return totals


def usage_alert_window_totals() -> dict:
    from usage_alert import window_hours
    return meter.totals(since=time.time() - window_hours() * 3600)


def _unavailable_response(e: AllProvidersUnavailable) -> JSONResponse:
    if not e.configured:
        # A deployment with no provider key: ours to fix, retrying cannot help.
        return JSONResponse({"error": "Transcription is unavailable right now.",
                             "retryable": False, "reason": "not_configured"},
                            status_code=503)
    retry_after = int(round(e.retry_after_s))
    return JSONResponse(
        {"error": "The transcription service is temporarily unavailable. "
                  "Your audio is safe; try again shortly.",
         "retryable": True, "reason": "providers_unavailable",
         "retry_after_s": retry_after},
        status_code=503, headers={"Retry-After": str(retry_after)})


async def _run(audio: bytes, content_type: str, language: Optional[str],
               keyterms: list, duration_s: float, idempotency_key: Optional[str],
               installation_id: str, scoped_key: Optional[str],
               free: bool = False, network_hash: Optional[str] = None,
               plan: str = client_auth.FREE_PLAN):
    """The provider work for one logical job. Runs at most once per key."""
    t0 = time.perf_counter()
    try:
        routed = await router.transcribe(audio, content_type, language, keyterms)
    except InputRejected as e:
        last = e.attempts[-1].provider if e.attempts else PREFERENCE[0]
        log.warning("provider=%s input rejected status=%d bytes=%d",
                    last, e.error.status, len(audio))
        meter.record(last, len(audio), duration_s, False, e.error.status,
                     idempotency_key=idempotency_key, installation_id=installation_id,
                     provider_called=True, attempts=len(e.attempts), plan=plan)
        response = JSONResponse({"error": e.error.message, "retryable": False,
                                 "reason": "audio_rejected"}, status_code=e.error.status)
        _remember_failure(scoped_key, response)
        return response
    except AllProvidersUnavailable as e:
        last = e.attempts[-1].provider if e.attempts else PREFERENCE[0]
        log.warning("no provider available attempts=%d retry_after=%.0f",
                    len(e.attempts), e.retry_after_s)
        meter.record(last, len(audio), duration_s, False, 503,
                     idempotency_key=idempotency_key, installation_id=installation_id,
                     provider_called=bool(e.attempts), attempts=len(e.attempts), plan=plan)
        response = _unavailable_response(e)
        _remember_failure(scoped_key, response)
        return response
    except AssertionError:
        # The mip_opt_out invariant tripped. Fail closed and loudly -- never
        # send the request without it, and never "fail over" around it.
        log.error("INVARIANT VIOLATION -- request refused")
        meter.record(PREFERENCE[0], len(audio), duration_s, False, 500,
                     idempotency_key=idempotency_key, installation_id=installation_id)
        return JSONResponse({"error": "internal configuration error",
                             "retryable": False}, status_code=500)

    result = routed.result
    elapsed_ms = int((time.perf_counter() - t0) * 1000)
    text = result.get("text") or ""
    # Meter on the larger of our probe and the provider's own measurement.
    # For a container we could not probe the probe is 0, and metering it at
    # zero made /v1/usage and the spend alert blind to that traffic.
    billed_s = max(duration_s, float(result.get("audio_duration_s") or 0.0))
    # chars only, never content
    log.info("ok provider=%s bytes=%d ms=%d chars=%d dur=%.1f probed=%.1f attempts=%d",
             routed.provider, len(audio), elapsed_ms, len(text), billed_s, duration_s,
             len(routed.attempts))
    meter.record(routed.provider, len(audio), billed_s, True, 200,
                 est_cost_inr=audio_limits.estimated_cost_inr(billed_s),
                 chars=len(text), idempotency_key=idempotency_key,
                 installation_id=installation_id, provider_called=True,
                 attempts=len(routed.attempts), failed_over=routed.failed_over,
                 # Counted against the free allowance: the audio's probed
                 # duration, the same figure admission checked; the provider's
                 # measurement only when the container could not be probed.
                 allowance_s=duration_s if duration_s > 0 else billed_s,
                 # Which tier paid for it, and an opaque per-day handle for the
                 # client network (never an address). Only free rows count
                 # toward the free-tier limits; owner rows count toward the
                 # owner's ceilings instead (owner.py).
                 free_tier=free, network_hash=network_hash if free else None,
                 plan=plan)

    # Checked here and nowhere else: this is the only path that adds cost, so
    # the aggregate cannot have crossed the threshold anywhere else. Never
    # allowed to affect the response -- a bookkeeping failure must not turn a
    # successful transcription into an error for the user.
    try:
        usage_alert.evaluate(
            usage_alert_window_totals().get("est_cost_inr", 0.0))
        if free:
            free_tier.after_free_job(auth.store.created_since(time.time() - 3600))
    except Exception:
        log.exception("usage alert evaluation failed")

    payload = {**result, "proxy_ms": elapsed_ms}
    if scoped_key:
        idempotency.put(scoped_key, payload)
        recent_failures.pop(scoped_key, None)
    return payload


@app.get("/v1/transcribe/status")
async def transcribe_status(
    request: Request,
    idempotency_key: Optional[str] = Header(None, alias="Idempotency-Key"),
):
    """
    Where a job the client already uploaded stands, without re-uploading it.

    200 finished (the same payload a replay returns) | 503 processing (still
    running; waits briefly first) | the job's recent failure | 404 unknown (the
    client must upload again, e.g. after a backend restart). Never starts
    provider work and never bills: auth is token-only for this route.
    """
    installation_id = request.scope.get("state", {}).get("installation_id")
    if not installation_id:
        return JSONResponse({"error": "This app needs to register again.",
                             "retryable": True, "reason": "unauthorized"}, status_code=401)
    if not idempotency_key:
        raise HTTPException(400, detail="Idempotency-Key required")
    scoped_key = f"{installation_id}:{idempotency_key}"

    cached = idempotency.get(scoped_key)
    if cached is not None:
        return {**cached, "replayed": True}
    task = inflight.get(scoped_key)
    if task is not None:
        try:
            outcome = await asyncio.wait_for(asyncio.shield(task), timeout=status_wait_s())
        except asyncio.TimeoutError:
            return JSONResponse(
                {"error": "Still transcribing. Checking again shortly.",
                 "retryable": True, "reason": "processing", "retry_after_s": 5},
                status_code=503, headers={"Retry-After": "5"})
        return {**outcome, "replayed": True} if isinstance(outcome, dict) else outcome
    failed = _recent_failure(scoped_key)
    if failed is not None:
        return failed
    return JSONResponse({"error": "No record of this transcription.", "retryable": True,
                         "reason": "unknown_job"}, status_code=404)


def free_tier_unknown_floor() -> int:
    """Shared with the allowance: how unprobeable audio is sized (bits/second)."""
    return allowance.unknown_floor_bps


def _allowance_exceeded_response(decision) -> JSONResponse:
    """
    429 with retryable=false: terminal for every Android build, which shows the
    `error` text as-is (the same handling as the daily quota). `reason` is the
    stable machine-readable code.
    """
    st = decision.status
    free_min = st.allowance_seconds // 60
    if st.remaining_seconds < 1:
        message = f"You have used your {free_min} free minutes for this month."
    else:
        message = (f"This audio is longer than your remaining free minutes this month "
                   f"({int(st.remaining_seconds // 60)} min {int(st.remaining_seconds % 60)} s left).")
    return JSONResponse(
        {"error": message, "retryable": False, "reason": ALLOWANCE_REASON,
         "allowance": {**st.as_dict(), "requested_seconds": round(decision.requested_seconds, 2)}},
        status_code=429)


@app.get("/v1/allowance")
async def allowance_status(request: Request):
    """
    This installation's free allowance for the current calendar month. Token
    only (no rate limit or quota); never starts provider work.
    """
    installation_id = request.scope.get("state", {}).get("installation_id")
    if not installation_id:
        return JSONResponse({"error": "This app needs to register again.",
                             "retryable": True, "reason": "unauthorized"}, status_code=401)
    return allowance.status(installation_id).as_dict()


def _service_available() -> bool:
    """Whether a new job could start right now: kill switch and global budget.
    A boolean only -- spend and budget figures are never sent to clients."""
    return transcription_enabled() and not auth.budget_exhausted()


@app.get("/v1/me")
async def me(request: Request):
    """
    This installation's plan and usage, for the app's Usage screen. Token only
    (no rate limit or quota); never starts provider work. `/v1/allowance` is
    unchanged and remains available to older builds.

    An owner sees `unlimited: true` and nothing else about limits: the owner's
    safety ceilings are an operator concern and are not exposed to clients.
    """
    state = request.scope.get("state", {})
    installation_id = state.get("installation_id")
    if not installation_id:
        return JSONResponse({"error": "This app needs to register again.",
                             "retryable": True, "reason": "unauthorized"}, status_code=401)
    per_file = {"max_duration_seconds": audio_limits.MAX_DURATION_SECONDS,
                "max_upload_bytes": audio_limits.MAX_UPLOAD_BYTES}
    service = {"available": _service_available()}
    if state.get("plan") == client_auth.OWNER_PLAN:
        return {"plan": client_auth.OWNER_PLAN, "unlimited": True,
                "monthly": None, "daily": None, "limits": per_file, "service": service}
    if state.get("plan") == client_auth.PRO_PLAN:
        ent = billing.entitlement(installation_id)
        if ent is not None:
            return _pro_view(installation_id, ent, per_file, service)
    body = {"plan": client_auth.FREE_PLAN, "unlimited": False,
            "monthly": allowance.status(installation_id).as_dict(),
            "daily": {"transcriptions_used": meter.provider_calls_since(
                          installation_id, time.time() - 86400),
                      "transcriptions_limit": auth.limits.transcriptions_per_day,
                      "window": "rolling_24h"},
            "limits": {**per_file, "requests_per_minute": auth.limits.requests_per_minute},
            "service": service}
    # Only for an installation that has a Play purchase which does not entitle
    # it right now (pending, on hold, paused, expired, revoked): so the app can
    # say why. Every other free response is exactly as before.
    latest = billing.latest_for(installation_id)
    if latest is not None:
        body["subscription"] = _subscription_view(latest)
    return body


def _subscription_view(sub) -> dict:
    from billing import PUBLIC_STATE, iso
    return {"state": PUBLIC_STATE.get(sub.state, "unknown"), "product_id": sub.product_id,
            "base_plan": sub.base_plan, "expires_at": iso(sub.expiry_at),
            "auto_renewing": sub.auto_renewing}


def _pro_view(installation_id: str, ent, per_file: dict, service: dict) -> dict:
    usage = pro_guard.usage(installation_id, (ent.window_start, ent.window_end))
    return {"plan": client_auth.PRO_PLAN, "unlimited": False,
            "subscription": _subscription_view(ent.subscription),
            "monthly": usage["cycle"],
            "daily": {**usage["day"],
                      "transcriptions_used": meter.provider_calls_since(
                          installation_id, time.time() - 86400),
                      "transcriptions_limit": auth.limits.pro_transcriptions_per_day,
                      "window": "calendar_day"},
            "limits": {**per_file, "requests_per_minute": auth.limits.pro_requests_per_minute},
            "service": service}


# ---- Irela Pro: purchase verification and Google's notifications ------------
BILLING_MAX_BODY = 8192


async def _small_json(request: Request) -> Optional[dict]:
    """The body as a JSON object, or None when too large or malformed."""
    try:
        if int(request.headers.get("content-length") or 0) > BILLING_MAX_BODY:
            return None
    except ValueError:
        return None
    raw = await request.body()
    if len(raw) > BILLING_MAX_BODY:
        return None
    try:
        body = json.loads(raw or b"{}")
    except (ValueError, UnicodeDecodeError):
        return None
    return body if isinstance(body, dict) else None


@app.post("/v1/billing/verify")
async def billing_verify(request: Request):
    """
    Verifies a Google Play purchase for THIS installation with Google, binds it,
    acknowledges it, and returns the resulting plan. Needs the installation's
    bearer token; body {"product_id", "purchase_token"}. 404 while billing is off.
    """
    installation_id = request.scope.get("state", {}).get("installation_id")
    if not installation_id:
        return JSONResponse({"error": "This app needs to register again.",
                             "retryable": True, "reason": "unauthorized"}, status_code=401)
    if not billing.verify_enabled:
        return JSONResponse({"error": "Not found.", "retryable": False,
                             "reason": "not_found"}, status_code=404)
    body = await _small_json(request)
    if body is None:
        return JSONResponse({"error": "Request not understood.", "retryable": False,
                             "reason": "bad_request"}, status_code=400)
    product_id, token = body.get("product_id"), body.get("purchase_token")
    if not isinstance(product_id, str) or not isinstance(token, str):
        return JSONResponse({"error": "Request not understood.", "retryable": False,
                             "reason": "bad_request"}, status_code=400)
    try:
        result = await billing.verify(installation_id, product_id, token)
    except client_auth.Rejection as r:
        headers = {"Retry-After": str(r.retry_after)} if r.retry_after else None
        return JSONResponse({"error": r.message, "retryable": r.retryable,
                             "reason": r.reason}, status_code=r.status, headers=headers)
    return {"plan": result.plan, "subscription": _subscription_view(result.subscription)}


@app.post("/v1/billing/rtdn")
async def billing_rtdn(request: Request):
    """
    Google Play Real-time Developer Notifications, pushed by Pub/Sub. Only a
    push carrying a Google-signed OIDC token for the configured audience and
    service account is accepted, and even then the purchase state is re-read
    from Google: the message body is never trusted. 404 while billing is off.
    """
    if not billing.rtdn_enabled:
        return JSONResponse({"error": "Not found.", "reason": "not_found"}, status_code=404)
    envelope = await _small_json(request)
    status, outcome = await billing.handle_rtdn(request.headers.get("authorization"),
                                                envelope or {})
    if status == 204:
        return Response(status_code=204)
    return JSONResponse({"reason": outcome}, status_code=status)


# The claim body is one short string; anything larger is refused unread.
OWNER_CLAIM_MAX_BODY = 4096


@app.post("/v1/owner/claim")
async def claim_owner(request: Request):
    """
    Redeems the owner claim code for THIS installation (see owner.py). Needs the
    installation's bearer token; the code arrives in the JSON body as `code`.
    Disabled (404) until OWNER_CLAIM_CODE_SHA256 is configured.
    """
    installation_id = request.scope.get("state", {}).get("installation_id")
    if not installation_id:
        return JSONResponse({"error": "This app needs to register again.",
                             "retryable": True, "reason": "unauthorized"}, status_code=401)
    try:
        declared = int(request.headers.get("content-length") or 0)
    except ValueError:
        declared = OWNER_CLAIM_MAX_BODY + 1
    if declared > OWNER_CLAIM_MAX_BODY:
        return JSONResponse({"error": "Request too large.", "retryable": False,
                             "reason": "too_large"}, status_code=413)
    raw = await request.body()
    if len(raw) > OWNER_CLAIM_MAX_BODY:
        return JSONResponse({"error": "Request too large.", "retryable": False,
                             "reason": "too_large"}, status_code=413)
    try:
        body = json.loads(raw or b"{}")
        code = body.get("code") if isinstance(body, dict) else None
    except (ValueError, UnicodeDecodeError):
        code = None
    network = request.client.host if request.client else None
    try:
        outcome = owner_claim.claim(installation_id, network,
                                    code if isinstance(code, str) else None)
    except client_auth.Rejection as r:
        headers = {"Retry-After": str(r.retry_after)} if r.retry_after else None
        return JSONResponse({"error": r.message, "retryable": r.retryable,
                             "reason": r.reason}, status_code=r.status, headers=headers)
    return {"plan": client_auth.OWNER_PLAN, "status": outcome}


def status_wait_s() -> float:
    return _env_float("STATUS_WAIT_S", 25.0)


@app.post("/v1/transcribe")
async def transcribe(
    request: Request,
    file: UploadFile = File(...),
    provider: str = Form("auto"),
    language: Optional[str] = Form(None),
    keyterm: Optional[List[str]] = Form(None),
    idempotency_key: Optional[str] = Header(None, alias="Idempotency-Key"),
):
    # Set by AuthGate, which has already refused anything unauthorized,
    # rate-limited, over quota or over budget -- before this body was read.
    installation_id = request.scope.get("state", {}).get("installation_id")
    if not installation_id:
        return JSONResponse({"error": "This app needs to register again.",
                             "retryable": True, "reason": "unauthorized"}, status_code=401)
    # Set by AuthGate from the database; never from anything the client sent.
    plan = request.scope.get("state", {}).get("plan") or client_auth.FREE_PLAN

    if provider not in ACCEPTED_PROVIDER_HINTS:
        raise HTTPException(400, detail="unknown provider")
    meter_name = PREFERENCE[0]

    # Idempotency keys are namespaced per installation: a key alone must never
    # return another installation's transcript. Retries from one job still
    # share the key, so JobRunner's retries still collapse.
    scoped_key = f"{installation_id}:{idempotency_key}" if idempotency_key else None

    # ---- kill switch: before reading the body, before any provider call ----
    if not transcription_enabled():
        log.warning("kill switch engaged: refusing request")
        meter.record(meter_name, 0, 0.0, False, 503, idempotency_key=idempotency_key,
                     installation_id=installation_id, plan=plan)
        # retryable=false so the client treats it as terminal and does NOT
        # enter a retry loop against a service we have deliberately stopped.
        return JSONResponse(
            {"error": "Transcription is temporarily unavailable.",
             "retryable": False, "reason": "disabled"},
            status_code=503)

    audio = await file.read()

    # ---- hard limits, cheapest first --------------------------------------
    try:
        audio_limits.check_bytes(len(audio))
        duration_s = audio_limits.probe_duration_seconds(
            audio, file.content_type or "")
        audio_limits.check_duration(duration_s)
    except audio_limits.LimitExceeded as e:
        log.warning("limit rejected status=%d bytes=%d", e.status, len(audio))
        meter.record(meter_name, len(audio), 0.0, False, e.status,
                     idempotency_key=idempotency_key, installation_id=installation_id,
                     plan=plan)
        # Terminal: a file that is too big stays too big, so retrying wastes
        # the client's retry budget and the user's time.
        return JSONResponse({"error": e.message, "retryable": False},
                            status_code=e.status)

    # ---- idempotency: a retry of a completed job is never re-billed --------
    if scoped_key:
        cached = idempotency.get(scoped_key)
        if cached is not None:
            log.info("replay provider=%s bytes=%d key=%s",
                     cached.get("provider"), len(audio), idempotency_key[:12])
            meter.record(cached.get("provider") or meter_name, len(audio), duration_s,
                         True, 200, est_cost_inr=0.0,
                         chars=len(cached.get("text") or ""),
                         replayed=True, idempotency_key=idempotency_key,
                         installation_id=installation_id, plan=plan)
            return {**cached, "replayed": True}

    joined = False
    task = inflight.get(scoped_key) if scoped_key else None
    if task is not None:
        joined = True
        log.info("join in-flight bytes=%d key=%s", len(audio), idempotency_key[:12])
    elif plan == client_auth.PRO_PLAN and billing.entitlement(installation_id) is not None:
        # ---- Pro: the free tier does not apply; Pro's cycle and daily ceilings do --
        # (An entitlement that lapsed since the gate falls through to FREE below.)
        ent = billing.entitlement(installation_id)
        requested_s = duration_s if duration_s > 0 else estimate_unknown_seconds(
            len(audio), audio_limits.MAX_DURATION_SECONDS, free_tier_unknown_floor())
        pro_decision = pro_guard.admit(installation_id, requested_s,
                                       (ent.window_start, ent.window_end), idempotency_key)
        if not pro_decision.admitted:
            meter.record(meter_name, len(audio), duration_s, False, 429,
                         idempotency_key=idempotency_key, installation_id=installation_id,
                         plan=plan)
            return JSONResponse({"error": pro_decision.message, "retryable": False,
                                 "reason": pro_decision.reason,
                                 "resets_at": pro_decision.resets_at}, status_code=429)
        task = asyncio.ensure_future(_run(
            audio, file.content_type or "application/octet-stream", language,
            keyterm or [], duration_s, idempotency_key, installation_id, scoped_key,
            free=False, network_hash=None, plan=plan))
        task.add_done_callback(lambda _t, h=pro_decision.handle: pro_guard.release(h))
        if scoped_key:
            inflight[scoped_key] = task
            task.add_done_callback(lambda _t, k=scoped_key: inflight.pop(k, None))
    elif plan == client_auth.OWNER_PLAN:
        # ---- owner: the free tier does not apply; the owner's own ceilings do --
        # Sized exactly as the free path sizes a job, reserved before any
        # provider call so concurrent owner jobs cannot overshoot a ceiling.
        requested_s = duration_s if duration_s > 0 else estimate_unknown_seconds(
            len(audio), audio_limits.MAX_DURATION_SECONDS, free_tier_unknown_floor())
        owner_decision = owner_guard.admit(installation_id, requested_s, idempotency_key)
        if not owner_decision.admitted:
            meter.record(meter_name, len(audio), duration_s, False, 429,
                         idempotency_key=idempotency_key, installation_id=installation_id,
                         plan=plan)
            return JSONResponse({"error": owner_decision.message, "retryable": False,
                                 "reason": owner_decision.reason}, status_code=429)
        task = asyncio.ensure_future(_run(
            audio, file.content_type or "application/octet-stream", language,
            keyterm or [], duration_s, idempotency_key, installation_id, scoped_key,
            free=False, network_hash=None, plan=plan))
        task.add_done_callback(lambda _t, h=owner_decision.handle: owner_guard.release(h))
        if scoped_key:
            inflight[scoped_key] = task
            task.add_done_callback(lambda _t, k=scoped_key: inflight.pop(k, None))
    else:
        # ---- free-tier protection: this network's day, and today's free spend --
        # The address comes from the ASGI scope, which uvicorn fills from the
        # LOCAL proxy's forwarding header (--forwarded-allow-ips); a
        # client-sent header is never trusted here. Only the salted, daily
        # rotating hash of it is ever stored.
        client_ip = request.client.host if request.client else None
        network_hash = free_tier.network_hash(client_ip)
        requested_s = duration_s if duration_s > 0 else estimate_unknown_seconds(
            len(audio), audio_limits.MAX_DURATION_SECONDS, free_tier_unknown_floor())
        verdict = free_tier.check(network_hash, requested_s,
                                  audio_limits.estimated_cost_inr(requested_s))
        if not verdict.admitted:
            log.warning("free tier refused reason=%s requested_s=%.1f", verdict.reason, requested_s)
            meter.record(meter_name, len(audio), duration_s, False, 429,
                         idempotency_key=idempotency_key, installation_id=installation_id)
            return JSONResponse({"error": verdict.message, "retryable": False,
                                 "reason": verdict.reason, "free_tier": verdict.detail},
                                status_code=429)

        # ---- free monthly allowance: the whole job must fit, or no provider --
        decision = allowance.admit(installation_id, duration_s, len(audio),
                                   audio_limits.MAX_DURATION_SECONDS, idempotency_key)
        if not decision.admitted:
            log.warning("allowance rejected requested=%.1f remaining=%.1f month=%s",
                        decision.requested_seconds, decision.status.remaining_seconds,
                        decision.status.month)
            meter.record(meter_name, len(audio), duration_s, False, 429,
                         idempotency_key=idempotency_key, installation_id=installation_id)
            return _allowance_exceeded_response(decision)
        task = asyncio.ensure_future(_run(
            audio, file.content_type or "application/octet-stream", language,
            keyterm or [], duration_s, idempotency_key, installation_id, scoped_key,
            free=True, network_hash=network_hash))
        # Released once the job's metering row exists (or it failed), so the
        # reservation and the counted row never overlap or leave a gap.
        handle = free_tier.reserve(network_hash, decision.charge_seconds,
                                   audio_limits.estimated_cost_inr(requested_s))
        task.add_done_callback(lambda _t, i=installation_id, d=decision: allowance.release(i, d))
        task.add_done_callback(lambda _t, h=handle: free_tier.release(h))
        if scoped_key:
            inflight[scoped_key] = task
            task.add_done_callback(lambda _t, k=scoped_key: inflight.pop(k, None))

    try:
        # shield: a client giving up must not cancel work another retry will
        # join, or that has already been paid for.
        outcome = await asyncio.wait_for(asyncio.shield(task), timeout=handler_wait_s())
    except asyncio.TimeoutError:
        log.info("still processing; asked client to retry key=%s",
                 (idempotency_key or "")[:12])
        return JSONResponse(
            {"error": "Still transcribing. Checking again shortly.",
             "retryable": True, "reason": "processing", "retry_after_s": 5},
            status_code=503, headers={"Retry-After": "5"})

    if joined and isinstance(outcome, dict):
        meter.record(outcome.get("provider") or meter_name, len(audio), duration_s,
                     True, 200, est_cost_inr=0.0, chars=len(outcome.get("text") or ""),
                     replayed=True, idempotency_key=idempotency_key,
                     installation_id=installation_id, plan=plan)
        return {**outcome, "replayed": True}
    return outcome


# ---- periodic re-check of unavailable providers -----------------------------
async def probe_unavailable_providers() -> None:
    """
    For each configured provider that is unavailable, run its cost-free
    credential probe (no audio, nothing billed). A good probe only brings the
    re-check forward -- the next real request is the trial that restores it,
    because a credential check cannot prove a quota problem is over.
    """
    for name, mod in PROVIDERS.items():
        if not router.configured(name) or health.is_available(name):
            continue
        probe = getattr(mod, "probe", None)
        if probe is None:
            continue
        try:
            verdict = await probe()
        except Exception:
            verdict = "inconclusive"
        if verdict == "ok":
            health.schedule_recheck_now(name)
        log.info("probe provider=%s verdict=%s", name, verdict)


async def _probe_loop(interval: float) -> None:
    while True:
        await asyncio.sleep(interval)
        try:
            await probe_unavailable_providers()
        except Exception:
            log.exception("provider probe failed")


@contextlib.asynccontextmanager
async def _lifespan(_app):
    interval = _env_float("PROVIDER_PROBE_INTERVAL_S", 300.0)
    task = asyncio.ensure_future(_probe_loop(interval)) if interval > 0 else None
    try:
        yield
    finally:
        if task:
            task.cancel()


app.router.lifespan_context = _lifespan
