#!/usr/bin/env python3
"""Create a fresh product + SecKill event for one load-test run and wait until it is ACTIVE.

Prints a single JSON line on stdout: {"eventId": <id>, "productId": <id>}.
Standard library only. Uses the seed admin account (override with --admin-email/--admin-pass).

  python3 loadtest/setup_event.py --base http://127.0.0.1:8080 --stock 5
"""
import argparse
import datetime as dt
import json
import sys
import time
import urllib.error
import urllib.request


def call(base, method, path, body=None, token=None, timeout=10):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(base + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            raw = r.read()
            return r.status, json.loads(raw) if raw else None
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw) if raw else None
        except ValueError:
            return e.code, raw.decode(errors="replace")


def iso(t):
    return t.astimezone(dt.timezone.utc).isoformat().replace("+00:00", "Z")


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--base", required=True)
    ap.add_argument("--stock", type=int, required=True)
    ap.add_argument("--admin-email", default="admin@ukm.edu.my")
    ap.add_argument("--admin-pass", default="Admin@123")
    ap.add_argument("--duration-min", type=int, default=120, help="event window length")
    ap.add_argument("--timeout", type=int, default=60, help="seconds to wait for ACTIVE")
    ap.add_argument("--label", default="loadtest")
    args = ap.parse_args()
    if args.stock <= 0:
        sys.exit("setup_event: --stock must be positive")

    st, r = call(args.base, "POST", "/api/auth/login",
                 {"email": args.admin_email, "password": args.admin_pass})
    if st != 200 or not isinstance(r, dict) or not r.get("token"):
        sys.exit(f"setup_event: admin login failed: HTTP {st} {r}")
    admin = r["token"]

    st, p = call(args.base, "POST", "/api/admin/products", {
        "name": f"{args.label} product {int(time.time())}",
        "description": "created by loadtest/setup_event.py",
        "price": 10.00,
        "totalStock": args.stock,
        "category": "loadtest",
    }, admin)
    if st not in (200, 201) or not isinstance(p, dict) or "id" not in p:
        sys.exit(f"setup_event: create product failed: HTTP {st} {p}")

    now = dt.datetime.now(dt.timezone.utc)
    st, e = call(args.base, "POST", "/api/admin/seckill-events", {
        "productId": p["id"],
        "seckillPrice": 1.00,
        "seckillStock": args.stock,
        "startTime": iso(now),
        "endTime": iso(now + dt.timedelta(minutes=args.duration_min)),
    }, admin)
    if st not in (200, 201) or not isinstance(e, dict) or "id" not in e:
        sys.exit(f"setup_event: create seckill event failed: HTTP {st} {e}")

    # The backend scheduler warms Redis stock and flips PENDING -> ACTIVE every 10 s.
    deadline = time.monotonic() + args.timeout
    status = e.get("status")
    while status != "ACTIVE":
        if time.monotonic() >= deadline:
            sys.exit(f"setup_event: event {e['id']} not ACTIVE after {args.timeout}s (status={status})")
        time.sleep(1)
        st, cur = call(args.base, "GET", f"/api/seckill/events/{e['id']}")
        status = cur.get("status") if st == 200 and isinstance(cur, dict) else status

    print(json.dumps({"eventId": e["id"], "productId": p["id"]}))


if __name__ == "__main__":
    main()
