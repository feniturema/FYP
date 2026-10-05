#!/usr/bin/env python3
"""Measure SSE token arrival times (standard library only) for acceptance A8 (docs/phases/P4b.md §9).

    python3 scripts/p4b/sse_timing.py --url http://127.0.0.1:58081/api/assistant/stream --token "$TOKEN"

POSTs one message and reads the event stream line by line as it arrives. Prints JSON
{"status", "firstMs", "lastMs", "count", "done", "error"}: firstMs / lastMs are the arrival times of the first and
last "token" events, in ms since the request was sent. The token is never printed.
"""
import argparse
import http.client
import json
import sys
import time
import urllib.parse
import uuid


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--url", required=True)
    ap.add_argument("--token", required=True)
    ap.add_argument("--message", default="hi")
    ap.add_argument("--timeout", type=float, default=60)
    a = ap.parse_args()
    if not a.token.strip():
        print("sse_timing: empty --token", file=sys.stderr)
        return 2

    u = urllib.parse.urlsplit(a.url)
    conn_cls = http.client.HTTPSConnection if u.scheme == "https" else http.client.HTTPConnection
    conn = conn_cls(u.hostname, u.port, timeout=a.timeout)
    body = json.dumps({"message": a.message, "conversationId": "sse-" + uuid.uuid4().hex[:16]})
    t0 = time.monotonic()
    conn.request("POST", u.path or "/", body=body, headers={
        "Content-Type": "application/json", "Accept": "text/event-stream", "Authorization": "Bearer " + a.token})
    resp = conn.getresponse()
    out = {"status": resp.status, "firstMs": None, "lastMs": None, "count": 0, "done": False, "error": None}
    if resp.status != 200:
        print(json.dumps(out))
        return 1

    event = None
    while True:
        raw = resp.readline()
        if not raw:
            break
        now_ms = round((time.monotonic() - t0) * 1000)
        line = raw.decode("utf-8").rstrip("\r\n")
        if line.startswith("event:"):
            event = line[6:].strip()
        elif line == "":
            if event == "token":
                out["count"] += 1
                out["firstMs"] = now_ms if out["firstMs"] is None else out["firstMs"]
                out["lastMs"] = now_ms
            elif event == "done":
                out["done"] = True
            elif event == "error":
                out["error"] = "error event"
            event = None
    conn.close()
    print(json.dumps(out))
    return 0 if out["done"] else 1


if __name__ == "__main__":
    sys.exit(main())
