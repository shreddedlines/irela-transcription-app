# -*- coding: utf-8 -*-
"""
One failure vocabulary for every provider.

The router's decisions depend on WHY a call failed, not on the HTTP status:

  TRANSIENT       timeout, network, 5xx         bounded retry, then fail over
  RATE_LIMITED    429                           obey Retry-After; another key in
                                                the same project is NOT another pool
  CREDENTIAL      401 / 403                     provider unhealthy, fail over
  QUOTA           402 / billing exhausted       provider unavailable, fail over,
                                                never rotate keys in the same project
  INPUT           400 / 413 / 415 / 422         the audio itself; another provider
                                                would not help -- never fail over
  NOT_CONFIGURED  no key in this deployment     skip silently
  UNKNOWN         anything else                 treated like TRANSIENT, bounded

Messages are for operators and are never shown to users verbatim; they never
contain audio, transcript text, keyterms or credentials.
"""

from typing import Optional

TRANSIENT = "transient"
RATE_LIMITED = "rate_limited"
CREDENTIAL = "credential"
QUOTA = "quota"
INPUT = "input"
NOT_CONFIGURED = "not_configured"
UNKNOWN = "unknown"

KINDS = {TRANSIENT, RATE_LIMITED, CREDENTIAL, QUOTA, INPUT, NOT_CONFIGURED, UNKNOWN}

_RETRYABLE_KINDS = {TRANSIENT, RATE_LIMITED, UNKNOWN}


class ProviderError(Exception):
    def __init__(self, status: int, message: str, retryable: Optional[bool] = None,
                 kind: str = UNKNOWN, retry_after_s: Optional[float] = None):
        super().__init__(message)
        assert kind in KINDS, kind
        self.status = status
        self.message = message
        self.kind = kind
        self.retryable = (kind in _RETRYABLE_KINDS) if retryable is None else retryable
        self.retry_after_s = retry_after_s


def parse_retry_after(value) -> Optional[float]:
    """Retry-After in seconds; HTTP-date forms are ignored (treated as absent)."""
    try:
        s = float(str(value).strip())
        return s if s >= 0 else None
    except (TypeError, ValueError):
        return None


def classify_http(provider: str, status: int, retry_after=None,
                  body_hint: str = "") -> ProviderError:
    """Maps a provider's non-2xx response to a ProviderError."""
    hint = (body_hint or "").lower()
    if status == 429:
        return ProviderError(429, f"{provider} rate limited", kind=RATE_LIMITED,
                             retry_after_s=parse_retry_after(retry_after))
    # Billing words only, deliberately not "insufficient": Deepgram's 403
    # INSUFFICIENT_PERMISSIONS is a credential problem, not an empty account.
    billing = any(w in hint for w in ("payment", "balance", "credit", "billing", "quota"))
    if status == 402 or (status in (401, 403) and billing):
        return ProviderError(503, f"{provider} quota/billing exhausted", kind=QUOTA)
    if status in (401, 403):
        return ProviderError(503, f"{provider} credential rejected", kind=CREDENTIAL)
    if status in (400, 413, 415, 422):
        return ProviderError(422, "audio rejected by provider", kind=INPUT)
    if status >= 500:
        return ProviderError(502, f"{provider} server error", kind=TRANSIENT)
    return ProviderError(502, f"{provider} status {status}", kind=UNKNOWN)
