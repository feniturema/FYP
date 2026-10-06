#!/usr/bin/env python3
"""Log in to an acceptance backend and print ONLY the JWT on stdout (docs/phases/P4b.md §7.6).

    python3 scripts/p4b/get_test_token.py --base http://127.0.0.1:58080 --email ... --password ...

Exits non-zero on a non-2xx status, an unparseable response, or a missing/empty token. Never prints the password,
the response body or the token anywhere except stdout.
"""
import argparse
import json
import sys
import urllib.error
import urllib.request


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--base", required=True)
    ap.add_argument("--email", required=True)
    ap.add_argument("--password", required=True)
    ap.add_argument("--timeout", type=float, default=30)
    a = ap.parse_args()

    body = json.dumps({"email": a.email, "password": a.password}).encode()
    req = urllib.request.Request(a.base.rstrip("/") + "/api/auth/login", data=body, method="POST",
                                 headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=a.timeout) as r:
            status, raw = r.status, r.read()
    except urllib.error.HTTPError as e:
        print(f"get_test_token: login failed with HTTP {e.code}", file=sys.stderr)
        return 1
    except (urllib.error.URLError, OSError) as e:
        print(f"get_test_token: login request failed: {type(e).__name__}", file=sys.stderr)
        return 1
    if not 200 <= status < 300:
        print(f"get_test_token: login failed with HTTP {status}", file=sys.stderr)
        return 1
    try:
        token = json.loads(raw).get("token")
    except (ValueError, AttributeError):
        print("get_test_token: login response is not a JSON object", file=sys.stderr)
        return 1
    if not isinstance(token, str) or not token.strip():
        print("get_test_token: login response has no token", file=sys.stderr)
        return 1
    print(token)
    return 0


if __name__ == "__main__":
    sys.exit(main())
