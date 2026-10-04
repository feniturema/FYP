#!/usr/bin/env python3
"""A6 per-request reconciliation after a backend crash (docs/phases/P2.md §9 A6, revised 2026-10-04).

Inputs, all for ONE SecKill event:
  --requests  k6 output of contention.js run with REQUEST_LOG=1 (one `REQ userId=.. status=.. result=..
              token=..` line per request; status 0 = no HTTP response)
  --orders    TSV with header: tracking_token, buyer_id, status   (SECKILL orders of the event)
  --outbox    TSV with header: order_id, user_id, status, created_at, sent_at   (order_outbox rows of the event)
  --stock N   the event's seckill_stock;  --sold-count N   its sold_count after the drain

Pass conditions (any violation -> exit 1; unreadable input -> exit 2):
  1. every request that received HTTP 202 has exactly one order whose tracking_token is the token in
     that response and whose buyer is that request's user (checked token by token, not by counts);
  2. an order without a received 202 is allowed only if its buyer's request got NO HTTP response
     (status 0) and the order has its committed outbox row; each such order is listed;
  3. orders <= stock, sold_count == orders, no duplicate buyer, no duplicate tracking token;
  4. every committed outbox row was processed: no NEW row left and outbox order_id <-> order
     tracking_token is one-to-one (no outbox row without order, no order without outbox row).

  python3 scripts/p2/verify_crash.py --requests k6.log --orders orders.tsv --outbox outbox.tsv --stock 50 --sold-count 50
  python3 scripts/p2/verify_crash.py --self-test     # negative cases must fail, the good case must pass
"""
import argparse
import csv
import re
import sys
from collections import Counter

REQ = re.compile(r"REQ userId=(\d+) status=(\d+) result=(\S+) token=(\S+)")


def parse_requests(lines):
    reqs = {}
    for line in lines:
        m = REQ.search(line)
        if not m:
            continue
        uid, status, result, token = m.group(1), int(m.group(2)), m.group(3), m.group(4)
        if uid in reqs:
            raise ValueError(f"user {uid} has more than one request line")
        reqs[uid] = {"status": status, "result": result, "token": None if token == "-" else token}
    return reqs


def check(reqs, orders, outbox, stock, sold_count):
    """Returns (ok, report_lines). orders/outbox are lists of dicts (TSV rows)."""
    problems, notes = [], []
    tokens = Counter(o["tracking_token"] for o in orders)
    buyers = Counter(o["buyer_id"] for o in orders)
    by_token = {o["tracking_token"]: o for o in orders}
    outbox_by_id = {r["order_id"]: r for r in outbox}

    accepted = {uid: r for uid, r in reqs.items() if r["status"] == 202}
    no_response = {uid for uid, r in reqs.items() if r["status"] == 0}
    for uid, r in sorted(accepted.items()):
        o = by_token.get(r["token"])
        if r["token"] is None:
            problems.append(f"202 for user {uid} carried no trackingToken")
        elif o is None:
            problems.append(f"202 token {r['token']} (user {uid}) has NO order")
        elif o["buyer_id"] != uid:
            problems.append(f"202 token {r['token']} belongs to buyer {o['buyer_id']}, not user {uid}")

    accepted_tokens = {r["token"] for r in accepted.values()}
    extra = [o for o in orders if o["tracking_token"] not in accepted_tokens]
    for o in extra:
        uid, tok = o["buyer_id"], o["tracking_token"]
        ob = outbox_by_id.get(tok)
        if uid not in no_response:
            seen = reqs.get(uid)
            problems.append(f"order {tok} of user {uid} has no received 202 and its request "
                            f"{'got HTTP ' + str(seen['status']) if seen else 'is unknown'}")
        elif ob is None:
            problems.append(f"order {tok} of user {uid} (no response) has no outbox row")
        else:
            notes.append(f"explained: order {tok} user {uid}: request got no HTTP response, outbox row "
                         f"committed {ob['created_at']}, sent {ob['sent_at']}, order status {o['status']}")

    dup_tokens = [t for t, n in tokens.items() if n > 1]
    dup_buyers = [b for b, n in buyers.items() if n > 1]
    if dup_tokens:
        problems.append(f"duplicate tracking tokens: {dup_tokens}")
    if dup_buyers:
        problems.append(f"duplicate buyers: {dup_buyers}")
    if len(orders) > stock:
        problems.append(f"orders {len(orders)} > stock {stock}")
    if sold_count != len(orders):
        problems.append(f"sold_count {sold_count} != orders {len(orders)}")

    new_rows = [r["order_id"] for r in outbox if r["status"] != "1"]
    if new_rows:
        problems.append(f"outbox rows not processed (status NEW): {new_rows}")
    unprocessed = sorted(set(outbox_by_id) - set(by_token))
    if unprocessed:
        problems.append(f"committed outbox rows without an order: {unprocessed}")
    orphan = sorted(set(by_token) - set(outbox_by_id))
    if orphan:
        problems.append(f"orders without an outbox row: {orphan}")

    summary = (f"requests={len(reqs)} received_202={len(accepted)} no_response={len(no_response)} "
               f"other={len(reqs) - len(accepted) - len(no_response)} | orders={len(orders)} "
               f"(matched to a received 202: {len(orders) - len(extra)}, without a received 202: {len(extra)}) "
               f"| outbox={len(outbox)} | stock={stock} sold_count={sold_count}")
    report = [summary] + notes + [f"PROBLEM: {p}" for p in problems]
    return not problems, report


def read_tsv(path):
    with open(path, newline="") as f:
        return list(csv.DictReader(f, delimiter="\t"))


def self_test():
    """Negative cases must FAIL and the good cases must PASS; returns 0 when every expectation holds."""
    def req(uid, status, token=None):
        result = {202: "ACCEPTED", 409: "SOLD_OUT", 0: "UNPARSEABLE"}[status]
        return f"REQ userId={uid} status={status} result={result} token={token or '-'}"

    def order(tok, uid):
        return {"tracking_token": tok, "buyer_id": uid, "status": "PAID"}

    def ob(tok, uid, status="1"):
        return {"order_id": tok, "user_id": uid, "status": status, "created_at": "t", "sent_at": "t"}

    base_reqs = [req("1", 202, "t1"), req("2", 202, "t2"), req("3", 0), req("4", 409)]
    base_orders = [order("t1", "1"), order("t2", "2"), order("t3", "3")]
    base_outbox = [ob("t1", "1"), ob("t2", "2"), ob("t3", "3")]
    cases = [
        ("good: every 202 has its order; one no-response order explained by its outbox row",
         base_reqs, base_orders, base_outbox, 3, 3, True),
        ("missing order for a received 202 (t2 dropped)",
         base_reqs, [order("t1", "1"), order("t3", "3")], [ob("t1", "1"), ob("t3", "3")], 3, 2, False),
        ("missing order for a received 202 while counts still match (t2 swapped for an unknown order)",
         base_reqs, [order("t1", "1"), order("tx", "2"), order("t3", "3")],
         [ob("t1", "1"), ob("tx", "2"), ob("t3", "3")], 3, 3, False),
        ("extra order for a user who got HTTP 409",
         base_reqs, base_orders + [order("t4", "4")], base_outbox + [ob("t4", "4")], 5, 4, False),
        ("duplicate order for one buyer",
         base_reqs, base_orders + [order("t1b", "1")], base_outbox + [ob("t1b", "1")], 5, 4, False),
        ("committed outbox row never turned into an order",
         base_reqs, base_orders, base_outbox + [ob("t5", "3")], 5, 3, False),
        ("outbox row still NEW",
         base_reqs, base_orders, [ob("t1", "1"), ob("t2", "2"), ob("t3", "3", "0")], 3, 3, False),
        ("more orders than stock",
         base_reqs, base_orders, base_outbox, 2, 3, False),
        ("sold_count disagrees with orders",
         base_reqs, base_orders, base_outbox, 3, 2, False),
    ]
    failures = 0
    for name, reqs, orders, outbox, stock, sold, expect in cases:
        ok, report = check(parse_requests(reqs), orders, outbox, stock, sold)
        good = ok == expect
        failures += 0 if good else 1
        print(f"{'ok  ' if good else 'BAD '} expect {'PASS' if expect else 'FAIL'} got {'PASS' if ok else 'FAIL'}: {name}")
        for line in report[1:]:
            print(f"       {line}")
    print(f"self-test: {'PASS' if not failures else f'FAIL ({failures})'}")
    return 0 if not failures else 1


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--self-test", action="store_true")
    ap.add_argument("--requests")
    ap.add_argument("--orders")
    ap.add_argument("--outbox")
    ap.add_argument("--stock", type=int)
    ap.add_argument("--sold-count", type=int)
    args = ap.parse_args()
    if args.self_test:
        return self_test()
    if None in (args.requests, args.orders, args.outbox, args.stock, args.sold_count):
        ap.error("--requests, --orders, --outbox, --stock and --sold-count are required")
    try:
        with open(args.requests, errors="replace") as f:
            reqs = parse_requests(f)
        orders, outbox = read_tsv(args.orders), read_tsv(args.outbox)
    except (OSError, ValueError, KeyError) as e:
        print(f"verify_crash: cannot read input: {e}", file=sys.stderr)
        return 2
    if not reqs:
        print("verify_crash: no REQ lines (run contention.js with REQUEST_LOG=1)", file=sys.stderr)
        return 2
    ok, report = check(reqs, orders, outbox, args.stock, args.sold_count)
    print("\n".join(report))
    print(f"verify_crash: {'PASS' if ok else 'FAIL'}")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
