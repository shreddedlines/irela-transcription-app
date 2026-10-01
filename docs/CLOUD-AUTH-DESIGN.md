# Client authentication and abuse protection

Implemented in the backend's `client_auth.py` and, on Android, in
`InstallationCredentials.kt` and `BackendProvider.kt`.

## Why

The backend holds the speech-to-text provider keys, so anyone who can call
`/v1/transcribe` spends that credit. Without a client check, knowing the URL
would be enough, and per-request limits cap the cost of one call but not the
number of calls.

## Assets

1. **Provider credit** — the primary target.
2. **User audio** — must not be readable by other callers.
3. **Service availability** — for legitimate installs.

Provider credentials are out of scope here: they never leave the backend
(guarded by tests that scan the app's sources for credentials).

## Threat model

| # | Threat | Mitigation | Residual risk |
|---|---|---|---|
| T1 | Backend URL discovered (decompiled APK, proxy on own phone); requests scripted to spend credit | Every `/v1/transcribe` needs a bearer token issued by our backend, checked in ASGI middleware **before the body is read**. No token → 401, no provider call, no DB write | The attacker can register a token the way the app does → T3 |
| T2 | One installation floods requests (bug or abuse) | Per-installation **requests/minute** and **provider calls/day**. Answered **429, `retryable:false`** — JobRunner never retries | Bounded to one installation's quota |
| T3 | Many installations registered to multiply quota | Registration limited **per client address per hour** and **globally per hour**; optional **global daily spend budget** refuses provider calls once reached | Cannot be eliminated without platform attestation — any secret in an APK is extractable, so a script can always imitate registration. The budget caps the worst case. Upgrade path: Play Integrity (free; needs Play distribution) |
| T4 | Token copied off one device | Per-installation, revocable (403 terminal). Stored in app-private prefs, **excluded from cloud backup and device transfer** | A rooted device can read its own token; damage limited to that installation's quota |
| T5 | Captured request replayed / cached result fetched | TLS on the wire. Idempotency keys **namespaced per installation**, so a key alone never returns another installation's transcript | — |
| T6 | Token is, or becomes, a provider credential | 256-bit random value generated server-side. Only its **SHA-256** is stored. Never forwarded to a provider, never logged, never in an error body | — |
| T7 | Flood of unauthenticated requests | Rejected in middleware without reading the body or touching the database | Network-level DoS is the host/proxy's concern |
| T8 | Registration flood fills the database | Global registrations/hour cap; each row is ~100 bytes | Bounded |

**Known, accepted exposure:** `/healthz` is unauthenticated, and `/v1/usage` is
too unless `ADMIN_TOKEN` is set. They expose configuration and aggregate
counts, never content or tokens. Keep them unindexed, set `ADMIN_TOKEN`, or put
them behind the proxy's allow-list.

## Flow

```
first transcription on an installation
  Android ── POST /v1/installations ─────────────▶ backend
             (no body, no device identifiers)       per-address + global/hour limits
          ◀─ 201 {installation_id, token} ───────── token = secrets.token_urlsafe(32)
                                                     DB stores sha256(token) only
  app saves the pair in private prefs (no backup, no device transfer)

every transcription
  Android ── POST /v1/transcribe ────────────────▶ AuthGate (ASGI, body NOT read)
             Authorization: Bearer <token>            1. token known?        else 401
             Idempotency-Key: <job id>                2. revoked?            → 403
                                                      3. cached replay?      → pass (free)
                                                      4. per-minute limit    → 429
                                                      5. per-day quota       → 429
                                                      6. daily budget        → 503
                                                   handler
                                                      kill switch → size → duration
                                                      → idempotency (per install) → provider router
```

### Status codes the app must handle

| Status | `reason` | `retryable` | App behaviour |
|---|---|---|---|
| 401 | `unauthorized` | true | Discard stored token; the job's next attempt registers anew. Bounded by `MAX_RETRIES` — a backend that keeps rejecting stops after 3 attempts |
| 403 | `revoked` | false | Terminal |
| 429 | `rate_limited` | false | Terminal |
| 429 | `quota` | false | Terminal |
| 429 | `registration_limited` | false | Terminal; no audio is uploaded |
| 503 | `budget` | false | Terminal |
| 503 | `disabled` | false | Terminal (kill switch) |

## Interaction with retries and idempotency

- One job = one idempotency key across all retries. The backend
  scopes it as `installation_id:key`.
- **A retry of an already-completed job bypasses rate and quota limits.** If a
  paid transcription's response is lost on a bad network, the retry is served
  from cache; refusing it on quota would discard a transcript already paid for.
  Another installation reusing the same key does not get this bypass.
- Only requests that reach a provider count toward the daily quota. Limit
  rejections (size, duration, empty) and replays do not.
- On-device content-hash dedupe runs before any network call.

## Configuration

| Variable | Default | Meaning |
|---|---|---|
| `CLIENT_REQUESTS_PER_MINUTE` | 6 | Per installation. Normal use: 1 attempt + up to 2 retries per job |
| `CLIENT_TRANSCRIPTIONS_PER_DAY` | 40 | Provider calls per installation per rolling 24 h |
| `REGISTRATIONS_PER_IP_PER_HOUR` | 10 | In memory; requires correct forwarded-address handling (see deployment) |
| `REGISTRATIONS_PER_HOUR` | 200 | Global, persisted |
| `DAILY_BUDGET_INR` | unset (off) | Refuse provider calls once 24 h estimated spend reaches this |

## Operations

**Revoke an installation** (no admin API by design):

```bash
sqlite3 /var/lib/apex/metering.sqlite3 \
  "UPDATE installations SET revoked = 1 WHERE id = '<installation_id>';"
```

Find heavy users without touching content:

```bash
sqlite3 /var/lib/apex/metering.sqlite3 \
  "SELECT installation_id, COUNT(*) calls, ROUND(SUM(est_cost_inr),2) inr
     FROM requests WHERE provider_called = 1 AND ts >= strftime('%s','now') - 86400
     GROUP BY installation_id ORDER BY inr DESC LIMIT 20;"
```

## What this is not

Not user accounts, not device attestation, not a paid auth service, not
multi-instance. The per-minute and per-address counters are in process memory,
consistent with the single-instance design.
