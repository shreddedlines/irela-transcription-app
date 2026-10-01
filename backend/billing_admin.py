# -*- coding: utf-8 -*-
"""
Irela Pro administration, run ON THE SERVER over SSH, like owner_admin.py.
There is deliberately no network admin API.

    sudo -u apex env METERING_DB=/var/lib/apex/metering.sqlite3 \
        /opt/apex/venv/bin/python /opt/apex/backend/billing_admin.py list

    list                         every subscription: short ref, state, plan, expiry
    grant <installation> <days>  Pro for <days> (1-31) days without a Play
                                 purchase (support, testing); never renews
    revoke <ref>                 end a subscription's entitlement now (Free).
                                 The record is kept; nothing is deleted.
    key                          print a new BILLING_TOKEN_KEY (Fernet)

<ref> is the 10-character prefix `list` shows. Prints no purchase token, no
full token hash and no installation id beyond an 8-character prefix.
"""

import argparse
import datetime as _dt
import os
import secrets
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from billing import PUBLIC_STATE, BillingService, SubscriptionStore, entitles   # noqa: E402
from metering import Metering                                                   # noqa: E402


def _ts(v):
    return _dt.datetime.fromtimestamp(v).isoformat(timespec="minutes") if v else "-"


def cmd_list(store: SubscriptionStore, out=sys.stdout) -> int:
    now = time.time()
    subs = store.all()
    print(f"subscriptions: {len(subs)}", file=out)
    for s in subs:
        print(f"  {s.token_hash[:10]}  {s.source:6} {PUBLIC_STATE.get(s.state, s.state):16}"
              f" pro={'yes' if entitles(s.state, s.expiry_at, now) else 'no ':3}"
              f" plan={s.base_plan or '-':8} expires={_ts(s.expiry_at)}"
              f" installation={(s.installation_id or '-')[:8]}"
              f" ack={'yes' if s.acknowledged else 'no'}{' test' if s.test else ''}", file=out)
    return 0


def _find(store: SubscriptionStore, ref: str):
    matches = [s for s in store.all() if s.token_hash.startswith(ref)] if len(ref) >= 6 else []
    return matches[0] if len(matches) == 1 else None


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)
    sub.add_parser("list")
    g = sub.add_parser("grant")
    g.add_argument("installation_id")
    g.add_argument("days", type=int)
    r = sub.add_parser("revoke")
    r.add_argument("ref")
    sub.add_parser("key")
    args = p.parse_args(argv)

    if args.cmd == "key":
        from cryptography.fernet import Fernet
        print(Fernet.generate_key().decode("ascii"))
        return 0

    store = SubscriptionStore(Metering().path)
    if args.cmd == "list":
        return cmd_list(store)
    if args.cmd == "grant":
        # One billing-cycle window at most: a manual grant's allowance window
        # is the month ending at its expiry, so a longer grant would leave the
        # earlier weeks uncapped. Grant again for longer.
        if not 1 <= args.days <= 31:
            print("days must be between 1 and 31", file=sys.stderr)
            return 2
        now = time.time()
        ref = "manual-" + secrets.token_hex(16)
        store.insert_manual(ref, args.installation_id, now + args.days * 86400, now)
        store.event("manual_grant", ref, args.installation_id, detail=f"days={args.days}")
        print(f"granted: {ref[:10]} until {_ts(now + args.days * 86400)}")
        return 0
    if args.cmd == "revoke":
        s = _find(store, args.ref)
        if s is None:
            print("no single subscription matches that reference", file=sys.stderr)
            return 1
        BillingService(store, None).revoke(s.token_hash, is_hash=True)
        print(f"revoked: {s.token_hash[:10]}")
        return 0
    return 2


if __name__ == "__main__":
    sys.exit(main())
