# -*- coding: utf-8 -*-
"""
AssemblyAI provider -- the production BACKUP, used only through the router
(provider_router.py) when Deepgram is unavailable.

Its frozen config, chosen in the same evaluation, is universal-3-5-pro.
The router passes the same language hint the app sends ("hi"), so no language
detection runs in production; detection is used only when no hint is given.

Error classification follows AssemblyAI's documented HTTP semantics (401 bad
key, 429 throttled, 5xx server) plus billing words in the error body. It has
NOT been exercised against the live API in this project: verify it with a
controlled failover test before relying on it.
"""

import asyncio
import os
import time
from typing import Optional

import httpx

from providers import errors
from providers.errors import ProviderError  # noqa: F401  (re-exported)

NAME = "assemblyai"
BASE = "https://api.assemblyai.com/v2"
POLL_INTERVAL_S = 2.0

FROZEN = {"speech_models": ["universal-3-5-pro"]}


def configured() -> bool:
    return bool(os.environ.get("ASSEMBLYAI_API_KEY", "").strip())


def _api_key() -> str:
    key = os.environ.get("ASSEMBLYAI_API_KEY", "").strip()
    if not key:
        raise ProviderError(503, "assemblyai not configured", retryable=False,
                            kind=errors.NOT_CONFIGURED)
    return key


def build_config(upload_url: str, language: Optional[str] = None,
                 keyterms: Optional[list] = None) -> dict:
    cfg = {"audio_url": upload_url, **FROZEN}
    if language:
        cfg["language_code"] = language
    else:
        cfg["language_detection"] = True
    if keyterms:
        # AssemblyAI's equivalent of keyterm prompting.
        cfg["keyterms_prompt"] = [str(t) for t in keyterms if str(t).strip()][:100]
    return cfg


def _raise_for(r) -> None:
    if 200 <= r.status_code < 300:
        return
    hint = ""
    try:
        hint = str(r.json().get("error", ""))
    except Exception:
        pass
    raise errors.classify_http(NAME, r.status_code,
                               retry_after=r.headers.get("retry-after"), body_hint=hint)


async def transcribe(audio: bytes, content_type: str,
                     language: Optional[str] = None,
                     keyterms: Optional[list] = None,
                     timeout_s: float = 300.0) -> dict:
    headers = {"authorization": _api_key()}
    deadline = time.monotonic() + timeout_s
    try:
        async with httpx.AsyncClient(timeout=min(timeout_s, 120.0)) as client:
            up = await client.post(f"{BASE}/upload", headers=headers, content=audio)
            _raise_for(up)
            cfg = build_config(up.json()["upload_url"], language, keyterms)
            job = await client.post(f"{BASE}/transcript", headers=headers, json=cfg)
            _raise_for(job)
            jid = job.json()["id"]
            # Bounded: the old loop polled forever if a job never left "queued".
            while time.monotonic() < deadline:
                poll = await client.get(f"{BASE}/transcript/{jid}", headers=headers)
                _raise_for(poll)
                p = poll.json()
                st = p.get("status")
                if st == "completed":
                    return {"text": p.get("text") or "", "provider": NAME,
                            "detected_language": p.get("language_code"),
                            "provider_ms": None,
                            "audio_duration_s": _safe_float(p.get("audio_duration"))}
                if st == "error":
                    # The job ran and could not transcribe this audio. Another
                    # provider is not expected to do better with the same file.
                    raise ProviderError(422, "assemblyai could not transcribe",
                                        kind=errors.INPUT)
                await asyncio.sleep(POLL_INTERVAL_S)
            raise ProviderError(504, "assemblyai processing deadline", kind=errors.TRANSIENT)
    except httpx.TimeoutException:
        raise ProviderError(504, "assemblyai timeout", kind=errors.TRANSIENT)
    except httpx.HTTPError:
        raise ProviderError(502, "assemblyai unreachable", kind=errors.TRANSIENT)


async def probe(timeout_s: float = 10.0) -> str:
    """Cost-free credential check (lists at most one transcript)."""
    if not configured():
        return "inconclusive"
    try:
        async with httpx.AsyncClient(timeout=timeout_s) as client:
            r = await client.get(f"{BASE}/transcript", params={"limit": 1},
                                 headers={"authorization": _api_key()})
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
