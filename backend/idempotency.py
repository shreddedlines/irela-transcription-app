# -*- coding: utf-8 -*-
"""
Idempotency storage, behind an interface.

WHY AN INTERFACE: the only implementation today keeps keys in process memory.
That is correct for a single instance and for development, and it is enough to
stop a retry storm from billing a provider three times for one job. It is NOT
correct for a multi-instance deployment: two replicas do not share memory, so a
retry landing on a different replica would miss the cache and re-bill.

PRODUCTION REQUIREMENT: a multi-instance deployment MUST supply a shared
implementation of `IdempotencyStore` (a database table is sufficient -- this
needs durability and atomic put-if-absent, not a cache product). Swapping it is
a constructor change; no call site moves. Deliberately not added now: no Redis,
no paid service.
"""

import threading
import time
from abc import ABC, abstractmethod
from typing import Optional


class IdempotencyStore(ABC):
    """Remembers the result of a completed job so a retry is not re-billed."""

    @abstractmethod
    def get(self, key: str) -> Optional[dict]:
        ...

    @abstractmethod
    def put(self, key: str, value: dict) -> None:
        ...

    @property
    @abstractmethod
    def is_shared(self) -> bool:
        """False when the store cannot be trusted across instances."""


class InProcessIdempotencyStore(IdempotencyStore):
    """
    Single-instance / development implementation.

    `is_shared` is False so a deployment check can refuse to run multiple
    replicas against it rather than silently double-billing.
    """

    def __init__(self, ttl_seconds: int = 3600):
        self._ttl = ttl_seconds
        self._data: dict = {}
        self._lock = threading.Lock()

    @property
    def is_shared(self) -> bool:
        return False

    def get(self, key: str) -> Optional[dict]:
        with self._lock:
            hit = self._data.get(key)
            if not hit:
                return None
            ts, value = hit
            if time.time() - ts > self._ttl:
                self._data.pop(key, None)
                return None
            return value

    def put(self, key: str, value: dict) -> None:
        with self._lock:
            self._data[key] = (time.time(), value)
            cutoff = time.time() - self._ttl
            for k in [k for k, (t, _) in self._data.items() if t < cutoff]:
                self._data.pop(k, None)
