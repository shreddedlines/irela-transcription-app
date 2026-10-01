# -*- coding: utf-8 -*-
"""
Reconciles Irela Pro subscriptions with Google Play. Run by a systemd timer
(every 6 hours), as the service user, with the service's environment:

    /opt/apex/venv/bin/python billing_sync.py

  * re-reads every live Play subscription not verified in the last 12 hours,
    so a missed notification cannot leave a lapsed renewal entitled for long;
  * retries acknowledgements Google has not recorded (it refunds after 3 days);
  * applies voided purchases (refunds, chargebacks): those become Free.

It never deletes anything. With billing off it does nothing and says so.
Prints counts only -- never a token, hash or installation id.
"""

import asyncio
import json
import logging
import sys

from billing import BillingService, SubscriptionStore
from metering import Metering


def main() -> int:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    store = SubscriptionStore(Metering().path)
    service = BillingService.from_env(store)
    report = asyncio.run(service.sync())
    print(json.dumps(report, sort_keys=True))
    return 0 if report.get("failed", 0) == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
