#!/usr/bin/env python3
"""Read-only production cutover precheck for P2 (docs/phases/P2.md §10.1). Run it BEFORE V2.

Checks (any failure -> exit 1; usage or connection errors -> exit 2):
  1. no running or imminent SecKill event (end_time >= now AND start_time <= now + 30 min);
  2. the legacy Redis Stream seckill:orders is fully consumed (XPENDING count 0, group lag 0);
  3. every Stream entry's token exists as orders.tracking_token;
  4. no duplicate historical SECKILL orders per (buyer_id, ref_id) — V2's unique key needs this;
  5. every event that has not ended has orders(ref_id) <= seckill_stock (the future sold_count);
  6. prints the backup command for a person to run (this script never writes anything).

Only V1 columns are queried. MySQL and Redis are reached through the scripts/db/lib.sh targets:
  python3 scripts/p2/precheck_cutover.py --mysql temp:ftsm_ecommerce --redis temp
  python3 scripts/p2/precheck_cutover.py --mysql compose:<project>:<env-file>:<db> --redis compose:<project>:<env-file>
"""
import argparse
import json
import os
import subprocess
import sys

LIB = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "db", "lib.sh")
STREAM = "seckill:orders"
GROUP = "seckill-order-consumers"


class TargetError(Exception):
    pass


def lib_call(func, target, *args):
    cmd = ["bash", "-c", 'source "$0" && "$@"', LIB, func, target, *args]
    p = subprocess.run(cmd, capture_output=True, text=True)
    if p.returncode != 0:
        raise TargetError(f"{func} {target} {' '.join(args)[:80]} -> rc {p.returncode}: {p.stderr.strip()[:300]}")
    return p.stdout


def sql(target, query):
    out = lib_call("mysql_target_cli", target, "--batch", "--skip-column-names", "-e", query)
    return [line.split("\t") for line in out.splitlines() if line.strip()]


def redis_json(target, *args):
    out = lib_call("redis_target_cli", target, "--json", *args).strip()
    return json.loads(out) if out else None


def stream_exists(target):
    out = lib_call("redis_target_cli", target, "EXISTS", STREAM).strip()
    return out.endswith("1")


def check_events(mysql):
    rows = sql(mysql, "SELECT COUNT(*) FROM seckill_events "
                      "WHERE end_time >= NOW(6) AND start_time <= NOW(6) + INTERVAL 30 MINUTE")
    n = int(rows[0][0])
    return n == 0, f"running or starting within 30 min: {n}"


def check_stream_consumed(redis):
    if not stream_exists(redis):
        return True, f"{STREAM} does not exist (nothing to drain)"
    pending = redis_json(redis, "XPENDING", STREAM, GROUP)
    groups = redis_json(redis, "XINFO", "GROUPS", STREAM) or []
    group = next((g for g in groups if g.get("name") == GROUP), None)
    if group is None:
        return False, f"consumer group {GROUP} missing on {STREAM}"
    count = pending[0] if isinstance(pending, list) and pending else None
    lag = group.get("lag")
    return count == 0 and lag == 0, f"XPENDING count={count} lag={lag} entries-read={group.get('entries-read')}"


def check_stream_tokens(redis, mysql):
    if not stream_exists(redis):
        return True, "no stream entries"
    entries = redis_json(redis, "XRANGE", STREAM, "-", "+") or []
    tokens = []
    for _entry_id, fields in entries:
        kv = dict(zip(fields[::2], fields[1::2])) if isinstance(fields, list) else dict(fields)
        tokens.append(kv.get("token"))
    bad = [t for t in tokens if not t or not all(c.isalnum() or c == "-" for c in t)]
    if bad:
        return False, f"{len(bad)} entries without a usable token"
    found = set()
    for i in range(0, len(tokens), 500):
        chunk = tokens[i:i + 500]
        in_list = ",".join(f"'{t}'" for t in chunk)       # tokens validated as [A-Za-z0-9-] above
        found.update(r[0] for r in sql(mysql, f"SELECT tracking_token FROM orders WHERE tracking_token IN ({in_list})"))
    missing = [t for t in tokens if t not in found]
    return not missing, f"stream entries={len(tokens)} missing orders={len(missing)}" + (
        f" e.g. {missing[:3]}" if missing else "")


def check_duplicates(mysql):
    rows = sql(mysql, "SELECT buyer_id, ref_id, COUNT(*) FROM orders WHERE source_type='SECKILL' "
                      "GROUP BY 1,2 HAVING COUNT(*)>1")
    return not rows, f"duplicate (buyer_id, ref_id) groups: {len(rows)}" + (f" e.g. {rows[:3]}" if rows else "")


def check_open_event_counts(mysql):
    rows = sql(mysql, "SELECT e.id, e.seckill_stock, "
                      "(SELECT COUNT(*) FROM orders o WHERE o.source_type='SECKILL' AND o.ref_id=e.id) "
                      "FROM seckill_events e WHERE e.end_time >= NOW(6)")
    over = [r for r in rows if int(r[2]) > int(r[1])]
    return not over, f"open events={len(rows)} over stock={len(over)}" + (f" {over[:3]}" if over else "")


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--mysql", required=True, help="lib.sh MySQL target, e.g. temp:ftsm_ecommerce")
    ap.add_argument("--redis", required=True, help="lib.sh Redis target, e.g. temp")
    ap.add_argument("--db-name", default="ftsm_ecommerce", help="database name for the backup command")
    args = ap.parse_args()

    checks = [
        ("1 no running/imminent events", lambda: check_events(args.mysql)),
        ("2 legacy stream consumed", lambda: check_stream_consumed(args.redis)),
        ("3 every stream token has an order", lambda: check_stream_tokens(args.redis, args.mysql)),
        ("4 no duplicate SECKILL orders", lambda: check_duplicates(args.mysql)),
        ("5 open events: orders <= stock", lambda: check_open_event_counts(args.mysql)),
    ]
    failed = 0
    try:
        for name, fn in checks:
            ok, detail = fn()
            failed += 0 if ok else 1
            print(f"{'PASS' if ok else 'FAIL'}  {name}: {detail}")
    except (TargetError, ValueError, IndexError) as e:
        print(f"ERROR  {e}", file=sys.stderr)
        return 2
    print("INFO  6 backup (run by a person before deploying P2):")
    print(f"      mysqldump --single-transaction {args.db_name} > backup-$(date -u +%Y%m%dT%H%M%SZ).sql")
    print(f"precheck: {'PASS' if not failed else f'FAIL ({failed} check(s))'}")
    return 0 if not failed else 1


if __name__ == "__main__":
    sys.exit(main())
