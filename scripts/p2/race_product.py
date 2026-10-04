#!/usr/bin/env python3
"""A10: N verified users race to buy a B2C product whose stock is 1 (docs/phases/P2.md §9).

Registers and OTP-verifies --concurrency users (OTP codes are read from the backend log with the
e2e_test.py helpers), creates a product with totalStock=1 as the seed admin, then releases all
POST /api/orders requests at once. Pass: exactly one 200, every other response 400, the product's
stock is 0 and exactly one order references it. Exit 0 on pass, 1 on fail, 2 on setup errors.

  python3 scripts/p2/race_product.py --base http://127.0.0.1:28080 --concurrency 20 [--log FILE]

--log defaults to $P2_TMP/backend.log (the file scripts/p2/acceptance.sh streams the backend log to).
"""
import argparse
import os
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
from e2e_test import call, find_otp  # noqa: E402  (scripts/e2e_test.py)


def setup_error(msg):
    print(f"race_product: {msg}", file=sys.stderr)
    sys.exit(2)


def register_users(base, log, n, tag):
    tokens = []
    for i in range(n):
        email = f"race{tag}u{i}@siswa.ukm.edu.my"
        st, _ = call(base, "POST", "/api/auth/register",
                     {"name": f"Race {i}", "email": email, "password": "secret1"})
        code = None
        for _ in range(20):          # the log follower may lag a little behind the request
            code = find_otp(log, email)
            if code:
                break
            time.sleep(0.25)
        if not code:
            setup_error(f"no OTP for {email} in {log} (register HTTP {st})")
        st, r = call(base, "POST", "/api/auth/verify-otp", {"email": email, "code": code})
        if st != 200 or not isinstance(r, dict) or not r.get("token"):
            setup_error(f"verify-otp failed for {email}: HTTP {st}")
        tokens.append(r["token"])
    return tokens


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--base", required=True)
    ap.add_argument("--concurrency", type=int, default=20)
    ap.add_argument("--log", default=os.path.join(os.environ.get("P2_TMP", "/tmp"), "backend.log"))
    ap.add_argument("--admin-email", default="admin@ukm.edu.my")
    ap.add_argument("--admin-pass", default="Admin@123")
    args = ap.parse_args()
    if args.concurrency < 2:
        setup_error("--concurrency must be >= 2")
    if not os.path.isfile(args.log):
        print(f"race_product: backend log {args.log} not found", file=sys.stderr)
        return 2

    tag = time.strftime("%H%M%S")
    print(f"[1] register + OTP-verify {args.concurrency} users (tag {tag})")
    tokens = register_users(args.base, args.log, args.concurrency, tag)
    print(f"    verified {len(tokens)} users")

    st, r = call(args.base, "POST", "/api/auth/login",
                 {"email": args.admin_email, "password": args.admin_pass})
    if st != 200 or not isinstance(r, dict) or not r.get("token"):
        print(f"race_product: admin login failed: HTTP {st}", file=sys.stderr)
        return 2
    st, prod = call(args.base, "POST", "/api/admin/products",
                    {"name": f"race product {tag}", "price": 5.0, "totalStock": 1, "category": "Test"},
                    token=r["token"])
    pid = prod.get("id") if isinstance(prod, dict) else None
    if not pid:
        print(f"race_product: create product failed: HTTP {st} {prod}", file=sys.stderr)
        return 2
    print(f"[2] product #{pid} created with totalStock=1")

    barrier = threading.Barrier(len(tokens))

    def buy(tok):
        barrier.wait()
        return call(args.base, "POST", "/api/orders",
                    {"sourceType": "B2C_PRODUCT", "refId": pid, "paymentMethod": "FAKE_WALLET"}, token=tok)

    print(f"[3] {len(tokens)} concurrent POST /api/orders")
    with ThreadPoolExecutor(max_workers=len(tokens)) as ex:
        responses = list(ex.map(buy, tokens))
    statuses = [st for st, _ in responses]
    ok200, bad400 = statuses.count(200), statuses.count(400)
    print(f"    HTTP 200={ok200}  400={bad400}  other={len(statuses) - ok200 - bad400}  {sorted(statuses)}")
    messages = sorted({b.get("message") for st, b in responses if st == 400 and isinstance(b, dict)})
    print(f"    400 messages: {messages}")

    st, p = call(args.base, "GET", f"/api/products/{pid}")
    stock = p.get("totalStock") if isinstance(p, dict) else None
    orders = 0
    for tok in tokens:
        st, lst = call(args.base, "GET", "/api/orders", token=tok)
        if isinstance(lst, list):
            orders += sum(1 for o in lst if o.get("sourceType") == "B2C_PRODUCT" and o.get("refId") == pid)
    print(f"[4] product stock={stock}  orders for product={orders}")

    checks = [
        ("exactly one 200", ok200 == 1),
        ("all others 400", bad400 == len(tokens) - 1),
        ("product stock is 0", stock == 0),
        ("exactly one order", orders == 1),
    ]
    for label, cond in checks:
        print(("  PASS " if cond else "  FAIL ") + label)
    passed = all(c for _, c in checks)
    print(f"race_product: {'PASS' if passed else 'FAIL'}")
    return 0 if passed else 1


if __name__ == "__main__":
    sys.exit(main())
