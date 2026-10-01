# -*- coding: utf-8 -*-
"""
A spend threshold on estimated cost.

Deliberately not a notification service. For a beta, the two things that
actually matter are that the number is *visible* (`/v1/usage`) and that
crossing the line leaves a durable mark in the logs an operator can alert on
with whatever they already run. Anything more -- email, webhooks, paging -- is
a product in its own right and is not needed to avoid a surprise bill.

`est_cost_inr` is an ESTIMATE derived from duration and a published rate. It is
not an invoice, and it is not a substitute for the provider's own billing
dashboard. The threshold exists to catch runaway usage early, not to reconcile
spend to the rupee.
"""

import logging
import os
from typing import Optional

log = logging.getLogger("proxy.usage")

DEFAULT_WINDOW_HOURS = 24.0


def threshold_inr() -> Optional[float]:
    """
    The configured ceiling, or None when unset.

    Unset means "no threshold", not "zero": a misread here that returned 0.0
    would make every single request look like a breach and train the operator
    to ignore the log line.
    """
    raw = os.environ.get("USAGE_ALERT_INR", "").strip()
    if not raw:
        return None
    try:
        value = float(raw)
    except ValueError:
        log.warning("USAGE_ALERT_INR is not a number (%r); no threshold set", raw)
        return None
    if value <= 0:
        log.warning("USAGE_ALERT_INR must be positive (got %s); no threshold set", value)
        return None
    return value


def window_hours() -> float:
    raw = os.environ.get("USAGE_ALERT_WINDOW_HOURS", "").strip()
    if not raw:
        return DEFAULT_WINDOW_HOURS
    try:
        value = float(raw)
    except ValueError:
        return DEFAULT_WINDOW_HOURS
    return value if value > 0 else DEFAULT_WINDOW_HOURS


class UsageAlert:
    """
    Tracks whether the rolling window is over the threshold.

    Logs on the TRANSITION only. Logging on every request past the line would
    produce thousands of identical lines during exactly the incident an
    operator is trying to read, and the state is re-armed when spend falls back
    under, so a new spike in the next window is reported again.

    In-process state, like the idempotency store: with more than one instance
    each would keep its own flag and could log the crossing more than once.
    That is a duplicate log line, not a missed alert or a double charge, so it
    does not add to the single-instance restriction.
    """

    def __init__(self) -> None:
        self._breached = False

    @property
    def breached(self) -> bool:
        return self._breached

    def evaluate(self, total_inr: float, limit: Optional[float] = None) -> bool:
        """
        Returns True when the window is at or over the threshold.

        Called after a request is metered, so the number reflects the spend
        that has actually been recorded.
        """
        if limit is None:
            limit = threshold_inr()
        if limit is None:
            self._breached = False
            return False

        over = total_inr >= limit
        if over and not self._breached:
            log.warning(
                "USAGE_ALERT est_cost_inr=%.2f threshold_inr=%.2f window_hours=%.1f"
                " -- estimated spend has crossed the configured threshold",
                total_inr, limit, window_hours())
        elif not over and self._breached:
            log.info(
                "USAGE_ALERT_CLEARED est_cost_inr=%.2f threshold_inr=%.2f",
                total_inr, limit)
        self._breached = over
        return over

    def status(self, total_inr: float) -> dict:
        """The `/v1/usage` fields. Present even when no threshold is set, so a
        client never has to guess whether the feature exists."""
        limit = threshold_inr()
        return {
            "alert_threshold_inr": limit,
            "alert_window_hours": window_hours(),
            "alert_triggered": bool(limit is not None and total_inr >= limit),
        }
