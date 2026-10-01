# -*- coding: utf-8 -*-
"""
Deepgram provider. Holds the frozen production configuration.

This module is the ONLY place the Deepgram request is built. The Android client
names a provider and supplies audio, language hint and keyterms; it cannot set
or unset anything else. That is not tidiness, it is the security boundary:

  * A provider API key inside an APK is extractable, always. Keys live here.
  * `mip_opt_out=true` is the only thing standing between user audio and
    Deepgram Terms 3.2's "irrevocable, perpetual, transferable, sublicensable"
    licence to use submitted content for "training and testing our Models",
    which survives termination. Terms 3.3 makes the opt-out PER REQUEST:
    "This opt-out applies to the individual request in which it is set."
    One request without it is irreversible for that audio. So it is forced
    after all parameter assembly, and an attempt to weaken it raises.
"""

import os
from typing import Optional

import httpx

DEEPGRAM_URL = "https://api.deepgram.com/v1/listen"

# Frozen production configuration, validated on a reviewed Hindi/Hinglish
# corpus (wer_script 0.3008, CER 0.3242, omission 0.000, critical-term recall
# 0.912, hallucination 0.0). Re-validate on such a corpus before changing it.
FROZEN = {
    "model": "nova-3",
    "language": "hi",
    "smart_format": "false",
    "punctuate": "true",
    "numerals": "true",
}

MIP_OPT_OUT = "mip_opt_out"

# Caller-supplied keys we accept. Anything else is ignored rather than
# forwarded, so a client cannot reach Deepgram parameters we have not vetted.
CLIENT_SETTABLE = {"language", "keyterm"}


from providers import errors
from providers.errors import ProviderError  # noqa: F401  (re-exported)

NAME = "deepgram"
PROBE_URL = "https://api.deepgram.com/v1/projects"


def configured() -> bool:
    return bool(os.environ.get("DEEPGRAM_API_KEY", "").strip())


def _api_key() -> str:
    key = os.environ.get("DEEPGRAM_API_KEY", "").strip()
    if not key:
        raise ProviderError(503, "deepgram not configured", retryable=False,
                            kind=errors.NOT_CONFIGURED)
    return key


def build_params(language: Optional[str] = None,
                 keyterms: Optional[list] = None) -> dict:
    """
    Assemble the query parameters.

    Separated from the HTTP call so the invariant is unit-testable without a
    network or a credential.
    """
    params = dict(FROZEN)

    # A language hint may override only the language, and only with a value we
    # recognise as a language code -- never as a vehicle for other parameters.
    if language:
        lang = str(language).strip()
        if not lang.replace("-", "").isalpha() or len(lang) > 8:
            raise ProviderError(400, "invalid language code", retryable=False,
                                kind=errors.INPUT)
        params["language"] = lang

    # keyterm is a repeated parameter; httpx encodes a list as repeats.
    if keyterms:
        terms = [str(t).strip() for t in keyterms if str(t).strip()]
        if len(terms) > 100:                      # provider documented cap
            terms = terms[:100]
        if terms:
            params["keyterm"] = terms

    # ---- THE INVARIANT --------------------------------------------------
    # Forced last. Nothing above can have removed or weakened it, and an
    # explicit attempt to disable it is a programming error, not a preference.
    existing = params.get(MIP_OPT_OUT)
    if existing is not None and str(existing).lower() != "true":
        raise AssertionError(
            "mip_opt_out must be true: user audio would otherwise fall under "
            "Deepgram Terms 3.2's perpetual, sublicensable training licence"
        )
    params[MIP_OPT_OUT] = "true"
    return params


async def transcribe(audio: bytes, content_type: str,
                     language: Optional[str] = None,
                     keyterms: Optional[list] = None,
                     timeout_s: float = 300.0) -> dict:
    params = build_params(language, keyterms)
    headers = {
        "Authorization": f"Token {_api_key()}",
        "Content-Type": content_type or "application/octet-stream",
    }
    try:
        async with httpx.AsyncClient(timeout=timeout_s) as client:
            r = await client.post(DEEPGRAM_URL, params=params,
                                  headers=headers, content=audio)
    except httpx.TimeoutException:
        raise ProviderError(504, "deepgram timeout", kind=errors.TRANSIENT)
    except httpx.HTTPError:
        raise ProviderError(502, "deepgram unreachable", kind=errors.TRANSIENT)

    if r.status_code >= 300:
        # 401/403 credential, 402 insufficient credits, 429 rate limit, 5xx
        # transient, 400/413/415 the audio. Ours to fix, never surfaced verbatim.
        raise errors.classify_http(NAME, r.status_code,
                                   retry_after=_header(r, "retry-after"),
                                   body_hint=_error_hint(r))

    j = r.json()
    alts = (j.get("results", {}).get("channels") or [{}])[0].get("alternatives") or []
    meta = j.get("metadata", {})
    return {
        "text": alts[0].get("transcript", "") if alts else "",
        "provider": "deepgram",
        "detected_language": (alts[0].get("languages") or [None])[0] if alts else None,
        "provider_ms": int(float(meta.get("duration", 0)) * 0),  # not reported
        # Audio length as Deepgram measured it -- the figure it bills on. Used
        # for metering when the container could not be probed up front.
        "audio_duration_s": _safe_float(meta.get("duration")),
    }


def _header(r, name: str):
    headers = getattr(r, "headers", None) or {}
    try:
        return headers.get(name)
    except Exception:
        return None


def _error_hint(r) -> str:
    """Deepgram's err_code/err_msg only -- used for classification, never logged."""
    try:
        j = r.json()
        return f"{j.get('err_code', '')} {j.get('err_msg', '')}"
    except Exception:
        return ""


async def probe(timeout_s: float = 10.0) -> str:
    """
    Cost-free credential check (lists projects; no audio, no billing).

    Returns "ok", "credential", or "inconclusive". It cannot prove a
    quota/billing problem is over, so the router only uses it to schedule a
    real trial request, never to mark the provider healthy on its own.
    """
    if not configured():
        return "inconclusive"
    try:
        async with httpx.AsyncClient(timeout=timeout_s) as client:
            r = await client.get(PROBE_URL, headers={"Authorization": f"Token {_api_key()}"})
    except httpx.HTTPError:
        return "inconclusive"
    if 200 <= r.status_code < 300:
        return "ok"
    if r.status_code in (401, 403):
        return "credential"
    return "inconclusive"


def _safe_float(value) -> float:
    try:
        f = float(value)
        return f if f > 0 else 0.0
    except (TypeError, ValueError):
        return 0.0
