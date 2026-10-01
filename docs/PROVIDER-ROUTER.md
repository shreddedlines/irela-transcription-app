# Provider routing

The backend sends each transcription to a primary speech-to-text provider
(Deepgram) and fails over to a backup (AssemblyAI). The routing logic is
covered by tests that use stubbed providers. The backup's error classification
follows its documented HTTP semantics; verify failover against the live API
before relying on it.

## Decisions

- The **backend** chooses the provider. The app sends `provider=auto` (older
  builds send `deepgram`; the value is ignored for routing). A provider outage
  therefore never needs an APK update.
- The primary provider's configuration is fixed: `nova-3, language=hi,
  smart_format=false, punctuate=true, numerals=true, mip_opt_out=true`, keyterms
  when supplied. `mip_opt_out=true` is forced in `build_params`, and an
  invariant violation is refused, never failed over around.
- The backup uses `universal-3-5-pro` with the app's language hint.
- Keys exist only in the backend environment (`DEEPGRAM_API_KEY`,
  `ASSEMBLYAI_API_KEY`). A single project key per provider; the router never
  rotates keys, because keys in one project share one quota pool.

## Failure handling

| Failure (classified in `providers/errors.py`) | Router | Health |
|---|---|---|
| Timeout, network, 5xx | Retry with backoff (1 s, 2 s…, max 2 calls per provider), then next provider | DEGRADED; 3 consecutive → UNAVAILABLE 30 s, doubling to 1 h |
| 429 | Wait inline only if Retry-After ≤ 3 s; otherwise next provider | COOLING_DOWN until Retry-After (≤ 300 s) |
| 401 / 403 | Next provider immediately | UNAVAILABLE(credential) 300 s, doubling |
| 402 / billing words | Next provider immediately | UNAVAILABLE(quota) 900 s, doubling |
| 400 / 413 / 415 / job error (the audio) | **Stop.** 422, not retryable | No change |
| No key configured | Skip provider | Not stored |
| All providers exhausted | 503 `providers_unavailable`, retryable, `Retry-After` | — |

Overall request deadline 280 s (below the proxy's 330 s).

## Provider state machine

```
HEALTHY ──transient──▶ DEGRADED ──3 consecutive──▶ UNAVAILABLE(transient)
HEALTHY/DEGRADED ──429──▶ COOLING_DOWN(until Retry-After)
any ──401/403──▶ UNAVAILABLE(credential)        any ──402/billing──▶ UNAVAILABLE(quota)
UNAVAILABLE / COOLING_DOWN ──recheck time reached──▶ one trial request (others route elsewhere)
trial success / any success ──▶ HEALTHY   (Deepgram is preferred again for NEW requests)
trial failure ──▶ UNAVAILABLE, backoff doubled
input error ──▶ no change
```

State is persisted in the `provider_health` table of the metering database, so a
restart during an outage keeps routing around the broken provider. A background
probe (every `PROVIDER_PROBE_INTERVAL_S`, default 300 s) calls each
**unavailable** provider's cost-free GET endpoint (Deepgram `/v1/projects`,
AssemblyAI `/v2/transcript?limit=1`). A good probe only brings the re-check
forward; the next real request is the trial, because a credential check cannot
prove a billing problem is over. Healthy providers are never probed.

## Duplicate protection

1. **Completed**: idempotency cache (per installation) replays the result — no
   provider call, whichever provider produced it.
2. **Still running**: a retry with the same key joins the in-flight work
   (`asyncio.shield`); if it is not done within `HANDLER_WAIT_S` (100 s) the
   client gets 503 `processing`.
3. **Polling**: `GET /v1/transcribe/status` (token-only auth, no quota) returns
   the result, `processing`, the job's recent failure, or 404 `unknown_job`. The
   app polls with it instead of re-uploading.
4. **User retry**: the app's retry job inherits the original idempotency key
   (except after an empty transcript), so work that finished after the app gave
   up is replayed, not paid for again.

Limitation: the idempotency cache and in-flight map are in process memory
(single instance, 1 h TTL). A restart between a provider success and the client
receiving it can cause one repeat call.

## App behaviour

| Backend says | App |
|---|---|
| `providers_unavailable` | RETRYING with the provider's Retry-After (≤ 60 s), at most 2 waits, then FAILED `provider_unavailable`: audio kept, Try again offered. "Transcription is temporarily unavailable. Your audio is saved -- tap Try again in a few minutes." |
| `processing` | Poll status (≤ 40 polls, ≤ 15 s apart), no re-upload |
| `audio_rejected` / 413 | FAILED, not retried |

The app never falls back to the local engine.

## Configuration

| Variable | Default |
|---|---|
| `ASSEMBLYAI_API_KEY` | unset = no backup |
| `ROUTER_MAX_ATTEMPTS` | 2 |
| `ROUTER_BACKOFF_BASE_S` | 1.0 |
| `ROUTER_DEADLINE_S` | 280 |
| `ROUTER_PROVIDER_TIMEOUT_S` | 240 |
| `PROVIDER_TRANSIENT_TRIP` | 3 |
| `PROVIDER_TRANSIENT_RECHECK_S` | 30 |
| `PROVIDER_CREDENTIAL_RECHECK_S` | 300 |
| `PROVIDER_QUOTA_RECHECK_S` | 900 |
| `PROVIDER_MAX_RECHECK_S` | 3600 |
| `PROVIDER_PROBE_INTERVAL_S` | 300 (0 disables) |
| `HANDLER_WAIT_S` | 100 |
| `STATUS_WAIT_S` | 25 |
