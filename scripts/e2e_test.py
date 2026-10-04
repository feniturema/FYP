#!/usr/bin/env python3
"""
End-to-end test for the FTSM E-Commerce backend.

Exercises the full critical path against a RUNNING backend (+ MySQL + Redis):
  1. Admin login
  2. UKM email validation (non-UKM rejected)
  3. OTP registration + verification (reads OTP codes from the backend log)
  4. Admin creates a B2C product + a SecKill event
  5. SecKill anti-oversell concurrency test (N users race for STOCK units)
  6. Per-user duplicate-purchase guard
  7. Order persistence: a winner's SECKILL order appears (polled for up to 30 s, since P2 the
     order is written asynchronously via outbox -> Kafka -> listener)

Usage:
  python3 scripts/e2e_test.py [--base http://localhost:8080] [--log /tmp/ftsm-real.log]
                              [--users 60] [--stock 20]
"""
import argparse
import json
import re
import sys
import time
import urllib.request
import urllib.error
from concurrent.futures import ThreadPoolExecutor

def call(base, method, path, body=None, token=None):
    url = base + path
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            raw = r.read().decode()
            return r.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, raw

def find_otp(log_path, email):
    """Read the most recent OTP code logged for an email."""
    pat = re.compile(re.escape(email) + r"\s*=\s*(\d{6})")
    code = None
    with open(log_path, "r", errors="ignore") as f:
        for line in f:
            m = pat.search(line)
            if m:
                code = m.group(1)
    return code

def buy_result(status, body):
    """Business result of a SecKill buy response. The body is a SeckillBuyResponse for every
    contract status (202 / 200 before P2, 202 / 409 / 503 since P2), so it is read the same way."""
    if isinstance(body, dict) and body.get("result"):
        return body["result"]
    return f"HTTP{status}"

def ok(label, cond):
    print(("  PASS " if cond else "  FAIL ") + label)
    return cond

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://localhost:8080")
    ap.add_argument("--log", default="/tmp/ftsm-real.log")
    ap.add_argument("--admin-email", default="admin@ukm.edu.my")
    ap.add_argument("--admin-pass", default="Admin@123")
    ap.add_argument("--users", type=int, default=60)
    ap.add_argument("--stock", type=int, default=20)
    args = ap.parse_args()
    base = args.base
    results = []

    print("\n[1] Admin login")
    st, r = call(base, "POST", "/api/auth/login",
                 {"email": args.admin_email, "password": args.admin_pass})
    admin_token = r.get("token") if isinstance(r, dict) else None
    results.append(ok(f"admin login -> 200 with token (got {st})", st == 200 and admin_token))

    print("\n[2] Non-UKM email rejected on register")
    st, _ = call(base, "POST", "/api/auth/register",
                 {"name": "X", "email": "x@gmail.com", "password": "secret1"})
    results.append(ok(f"non-UKM register -> 403 (got {st})", st == 403))

    print(f"\n[3] Register + OTP-verify {args.users} students")
    tokens = []
    for i in range(args.users):
        email = f"student{i}@siswa.ukm.edu.my"
        call(base, "POST", "/api/auth/register",
             {"name": f"Student {i}", "email": email, "password": "secret1"})
        code = find_otp(args.log, email)
        if not code:
            continue
        st, r = call(base, "POST", "/api/auth/verify-otp", {"email": email, "code": code})
        if st == 200 and isinstance(r, dict) and r.get("token"):
            tokens.append(r["token"])
    results.append(ok(f"verified {len(tokens)}/{args.users} students via OTP", len(tokens) >= args.users))

    print("\n[4] Admin creates product + SecKill event")
    st, prod = call(base, "POST", "/api/admin/products",
                    {"name": "SecKill Test Item", "price": 99.0,
                     "totalStock": max(args.stock, 100), "category": "Test"},
                    token=admin_token)
    pid = prod.get("id") if isinstance(prod, dict) else None
    now = time.time()
    start_iso = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(now - 2))
    end_iso = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(now + 600))
    st, ev = call(base, "POST", "/api/admin/seckill-events",
                  {"productId": pid, "seckillPrice": 9.9, "seckillStock": args.stock,
                   "startTime": start_iso, "endTime": end_iso}, token=admin_token)
    eid = ev.get("id") if isinstance(ev, dict) else None
    results.append(ok(f"created product #{pid} and seckill event #{eid}", pid and eid))

    print("\n[4b] Wait for scheduler to warm stock + activate (<=15s)")
    active = False
    for _ in range(15):
        st, e = call(base, "GET", f"/api/seckill/events/{eid}")
        if isinstance(e, dict) and e.get("status") == "ACTIVE":
            active = True
            break
        time.sleep(1)
    results.append(ok("event became ACTIVE", active))

    print(f"\n[5] Concurrency: {len(tokens)} users race for {args.stock} units")
    outcomes = []
    def buy(tok):
        st, r = call(base, "POST", f"/api/seckill/{eid}/buy", token=tok)
        return buy_result(st, r)
    with ThreadPoolExecutor(max_workers=50) as ex:
        outcomes = list(ex.map(buy, tokens))
    accepted = outcomes.count("ACCEPTED")
    sold_out = outcomes.count("SOLD_OUT")
    print(f"    ACCEPTED={accepted}  SOLD_OUT={sold_out}  others={len(outcomes)-accepted-sold_out}")
    results.append(ok(f"exactly {args.stock} ACCEPTED (no oversell)", accepted == args.stock))

    print("\n[6] Duplicate-purchase guard (a winner buys again -> ALREADY_BOUGHT)")
    # find a token that won; re-buy must be rejected
    winner = None
    for tok, res in zip(tokens, outcomes):
        if res == "ACCEPTED":
            winner = tok
            break
    dup_ok = False
    if winner:
        st, r = call(base, "POST", f"/api/seckill/{eid}/buy", token=winner)
        dup_ok = buy_result(st, r) == "ALREADY_BOUGHT"
    results.append(ok("re-buy by winner -> ALREADY_BOUGHT", dup_ok))

    print("\n[7] Order reconciliation: wait (<=30s) for the order pipeline to persist orders")
    # A winner must see its SECKILL order in "My Orders"; poll instead of a fixed sleep.
    persisted_ok = False
    deadline = time.monotonic() + 30
    while winner and not persisted_ok and time.monotonic() < deadline:
        st, orders = call(base, "GET", "/api/orders", token=winner)
        persisted_ok = isinstance(orders, list) and any(
            o.get("sourceType") == "SECKILL" and o.get("status") in ("PAID", "PENDING")
            for o in orders)
        if not persisted_ok:
            time.sleep(1)
    results.append(ok("winner has a persisted SECKILL order", persisted_ok))

    print("\n=== SUMMARY ===")
    passed = sum(1 for x in results if x)
    print(f"{passed}/{len(results)} checks passed")
    sys.exit(0 if passed == len(results) else 1)

if __name__ == "__main__":
    main()
