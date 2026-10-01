# -*- coding: utf-8 -*-
"""
Owner administration, run ON THE SERVER over SSH. There is deliberately no
network admin API: this touches the metering database directly.

    sudo -u apex env METERING_DB=/var/lib/apex/metering.sqlite3 \
        /opt/apex/.venv/bin/python /opt/apex/backend/owner_admin.py list

    list                 owner installations and their usage today/this month
    downgrade <id>       make an installation free again (keeps its token)
    revoke <id>          revoke an installation outright (its token stops working)
    hash                 read a claim code from stdin and print its SHA-256,
                         for OWNER_CLAIM_CODE_SHA256 (the code is not echoed)

Prints installation ids, plans, timestamps and seconds. Never prints a token,
a token hash, a claim code or an address.
"""

import argparse
import datetime as _dt
import getpass
import hashlib
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from client_auth import FREE_PLAN, OWNER_PLAN, InstallationStore   # noqa: E402
from metering import Metering, default_db_path                      # noqa: E402
from owner import OwnerGuard                                        # noqa: E402


def _ts(v):
    return _dt.datetime.fromtimestamp(v).isoformat(timespec="seconds") if v else "-"


def cmd_list(store: InstallationStore, guard: OwnerGuard, out=sys.stdout) -> int:
    rows = store.list_installations(OWNER_PLAN)
    print(f"owner installations: {len(rows)} "
          f"(active {store.active_owner_count()})", file=out)
    for installation_id, revoked, plan, since, created in rows:
        u = guard.usage(installation_id)
        print(f"  {installation_id}  {'REVOKED' if revoked else 'active '}"
              f"  owner since {_ts(since)}  registered {_ts(created)}"
              f"  today {u['day_seconds'] / 3600:.2f}/{u['daily_seconds_limit'] / 3600:.0f} h"
              f"  month {u['month_seconds'] / 3600:.2f}/{u['monthly_seconds_limit'] / 3600:.0f} h",
              file=out)
    return 0


def _require(store: InstallationStore, installation_id: str) -> bool:
    if store.plan_of(installation_id) is None:
        print(f"no such installation: {installation_id}", file=sys.stderr)
        return False
    return True


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    p.add_argument("--db", default=None, help="metering database (default: $METERING_DB)")
    sub = p.add_subparsers(dest="cmd", required=True)
    sub.add_parser("list")
    for name in ("downgrade", "revoke"):
        sub.add_parser(name).add_argument("installation_id")
    sub.add_parser("hash")
    args = p.parse_args(argv)

    if args.cmd == "hash":
        code = getpass.getpass("owner claim code: ") if sys.stdin.isatty() else sys.stdin.readline()
        code = code.strip()
        if not code:
            print("empty code", file=sys.stderr)
            return 2
        print(hashlib.sha256(code.encode("utf-8")).hexdigest())
        return 0

    path = args.db or default_db_path()
    Metering(path)                          # applies any pending migrations
    store = InstallationStore(path)
    guard = OwnerGuard(path)
    if args.cmd == "list":
        return cmd_list(store, guard)
    if not _require(store, args.installation_id):
        return 1
    if args.cmd == "downgrade":
        store.set_plan(args.installation_id, FREE_PLAN, time.time())
        print(f"downgraded to free: {args.installation_id}")
    elif args.cmd == "revoke":
        store.revoke(args.installation_id)
        print(f"revoked: {args.installation_id}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
