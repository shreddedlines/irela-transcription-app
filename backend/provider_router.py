# -*- coding: utf-8 -*-
"""
Chooses which provider transcribes a request, and recovers from provider
failure without turning it into an application failure.

    for provider in PREFERENCE (deepgram, then assemblyai):
        skip if not configured, or unavailable and not yet due for a re-check
        up to MAX_ATTEMPTS calls:
            success                -> health HEALTHY, return
            INPUT (the audio)      -> stop: another provider would not help
            TRANSIENT/UNKNOWN      -> exponential backoff, retry, then next provider
            RATE_LIMITED           -> wait inline only if Retry-After is short,
                                      otherwise next provider (a second key in the
                                      same project is not a second quota pool)
            CREDENTIAL / QUOTA     -> next provider immediately
    nothing left -> AllProvidersUnavailable(retry_after_s)

Deepgram is first in PREFERENCE, so the moment it is healthy again -- a
successful trial after its recheck time, or a success on any call -- every NEW
request goes back to it. Duplicate protection is not here: idempotency in
app.py wraps this whole function, so a replay or an in-flight retry never
reaches a provider at all.

Keys are only ever read inside the provider modules. Nothing here or in any
response names a key.
"""

import asyncio
import logging
import os
import time
from dataclasses import dataclass, field
from typing import Callable, List, Mapping, Optional, Tuple

from providers import errors
from provider_health import ProviderHealth

log = logging.getLogger("router")

PREFERENCE: Tuple[str, ...] = ("deepgram", "assemblyai")


def _env_float(name: str, default: float) -> float:
    try:
        return float(os.environ.get(name, default))
    except (TypeError, ValueError):
        return default


@dataclass(frozen=True)
class RouterPolicy:
    max_attempts_per_provider: int = 2       # one retry
    backoff_base_s: float = 1.0
    backoff_max_s: float = 8.0
    inline_rate_limit_wait_max_s: float = 3.0
    deadline_s: float = 280.0                # under Caddy's 330 s proxy timeout
    provider_timeout_s: float = 240.0

    @staticmethod
    def from_env() -> "RouterPolicy":
        return RouterPolicy(
            max_attempts_per_provider=int(_env_float("ROUTER_MAX_ATTEMPTS", 2)),
            backoff_base_s=_env_float("ROUTER_BACKOFF_BASE_S", 1.0),
            deadline_s=_env_float("ROUTER_DEADLINE_S", 280.0),
            provider_timeout_s=_env_float("ROUTER_PROVIDER_TIMEOUT_S", 240.0),
        )


@dataclass
class Attempt:
    provider: str
    kind: Optional[str]       # None on success
    status: int


@dataclass
class Routed:
    result: dict
    provider: str
    attempts: List[Attempt] = field(default_factory=list)

    @property
    def failed_over(self) -> bool:
        return self.provider != PREFERENCE[0]


class AllProvidersUnavailable(Exception):
    def __init__(self, retry_after_s: float, attempts: List[Attempt], configured: bool):
        super().__init__("no transcription provider available")
        self.retry_after_s = retry_after_s
        self.attempts = attempts
        self.configured = configured


class InputRejected(Exception):
    """The audio itself was refused; failing over would not help."""
    def __init__(self, error: errors.ProviderError, attempts: List[Attempt]):
        super().__init__(error.message)
        self.error = error
        self.attempts = attempts


class ProviderRouter:
    def __init__(self, providers: Mapping[str, object], health: ProviderHealth,
                 policy: Optional[RouterPolicy] = None,
                 sleep: Callable = asyncio.sleep,
                 clock: Callable[[], float] = time.monotonic):
        self._providers = providers
        self.health = health
        self.policy = policy or RouterPolicy.from_env()
        self._sleep = sleep
        self._clock = clock

    def order(self) -> List[str]:
        return [p for p in PREFERENCE if p in self._providers]

    def configured(self, name: str) -> bool:
        mod = self._providers[name]
        check = getattr(mod, "configured", None)
        return bool(check()) if check else True

    def backoff(self, attempt: int) -> float:
        return min(self.policy.backoff_base_s * (2 ** attempt), self.policy.backoff_max_s)

    async def transcribe(self, audio: bytes, content_type: str,
                         language: Optional[str], keyterms: list) -> Routed:
        attempts: List[Attempt] = []
        start = self._clock()
        any_configured = False

        for name in self.order():
            if not self.configured(name):
                continue
            any_configured = True
            mod = self._providers[name]
            for attempt in range(self.policy.max_attempts_per_provider):
                remaining = self.policy.deadline_s - (self._clock() - start)
                if remaining <= 5:
                    break
                if not self.health.try_acquire(name):
                    break
                try:
                    result = await mod.transcribe(
                        audio=audio, content_type=content_type, language=language,
                        keyterms=keyterms,
                        timeout_s=min(self.policy.provider_timeout_s, remaining))
                except errors.ProviderError as e:
                    kind = getattr(e, "kind", errors.UNKNOWN)
                    attempts.append(Attempt(name, kind, e.status))
                    state = self.health.record_failure(name, kind, getattr(e, "retry_after_s", None))
                    log.warning("route provider=%s attempt=%d kind=%s status=%d -> %s",
                                name, attempt + 1, kind, e.status, state.state)
                    if kind == errors.INPUT:
                        raise InputRejected(e, attempts)
                    if kind == errors.NOT_CONFIGURED:
                        break
                    if kind == errors.RATE_LIMITED:
                        wait = getattr(e, "retry_after_s", None)
                        if (wait is not None and wait <= self.policy.inline_rate_limit_wait_max_s
                                and attempt + 1 < self.policy.max_attempts_per_provider):
                            await self._sleep(wait)
                            continue
                        break
                    if kind in (errors.CREDENTIAL, errors.QUOTA):
                        break
                    if attempt + 1 < self.policy.max_attempts_per_provider:
                        await self._sleep(self.backoff(attempt))
                        continue
                    break
                except BaseException:
                    self.health.release_trial(name)
                    raise
                self.health.record_success(name)
                attempts.append(Attempt(name, None, 200))
                if name != PREFERENCE[0]:
                    log.warning("route served by backup provider=%s", name)
                return Routed(result, name, attempts)

        retry_after = max(5.0, min(self.health.seconds_until_any(
            [p for p in self.order() if self.configured(p)]), 300.0))
        raise AllProvidersUnavailable(retry_after, attempts, any_configured)
