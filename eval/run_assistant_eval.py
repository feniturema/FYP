#!/usr/bin/env python3
"""Assistant tool-use evaluation (docs/phases/P4b.md §6.10, acceptance E2). Standard library only.

    python3 eval/run_assistant_eval.py --base http://127.0.0.1:58080 --token "$TOKEN" \\
        --out eval/results/assistant-$(date +%F).md
    python3 eval/run_assistant_eval.py --check-only     # validate the question set, no backend needed

Needs a backend running with the dev profile (the debug endpoint) and a real model (LLM_PROVIDER=deepseek).
For each question it opens a new conversation, reads the SSE stream (time to first token and total time), then
reads the tools actually called from GET /api/assistant/debug/tool-calls. Reports the share of questions whose
tool set matches exactly, the share that hit at least one expected tool (an empty expectation counts as a hit only
when no tool was called), and TTFT p50 / p95. An unknown tool name in the data or in a response is an error
(exit 2). The token is never written to the output.
"""
import argparse
import base64
import datetime as dt
import http.client
import json
import math
import os
import sys
import time
import urllib.parse
import urllib.request
import uuid

ALLOWED = {"search_products", "search_secondhand_items", "get_product_detail", "get_stock", "list_flash_sales",
           "my_orders"}
QUESTIONS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "assistant_questions.jsonl")


def load_questions(path: str) -> list:
    qs = [json.loads(line) for line in open(path, encoding="utf-8") if line.strip()]
    problems = []
    if len(qs) != 25:
        problems.append(f"expected 25 questions, found {len(qs)}")
    for q in qs:
        if q.get("lang") not in ("en", "ms", "zh"):
            problems.append(f"{q.get('id')}: lang {q.get('lang')!r}")
        unknown = set(q.get("expected_tools", [])) - ALLOWED
        if unknown:
            problems.append(f"{q.get('id')}: unknown expected tools {sorted(unknown)}")
    single = [q for q in qs if len(q["expected_tools"]) == 1]
    for tool in sorted(ALLOWED):
        n = sum(1 for q in single if q["expected_tools"][0] == tool)
        if n < 3:
            problems.append(f"{tool}: {n} single-tool questions (< 3)")
    if sum(1 for q in qs if len(q["expected_tools"]) > 1) < 3:
        problems.append("fewer than 3 multi-tool questions")
    if sum(1 for q in qs if not q["expected_tools"]) < 3:
        problems.append("fewer than 3 chit-chat questions")
    if len({q["id"] for q in qs}) != len(qs):
        problems.append("duplicate ids")
    if problems:
        raise SystemExit("question set invalid:\n  - " + "\n  - ".join(problems))
    return qs


def user_id_from_jwt(token: str) -> str:
    payload = token.split(".")[1]
    claims = json.loads(base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4)))
    return str(claims["sub"])


def ask(base: str, token: str, conv: str, message: str, timeout: float) -> dict:
    u = urllib.parse.urlsplit(base)
    conn_cls = http.client.HTTPSConnection if u.scheme == "https" else http.client.HTTPConnection
    conn = conn_cls(u.hostname, u.port, timeout=timeout)
    t0 = time.monotonic()
    conn.request("POST", "/api/assistant/stream", body=json.dumps({"message": message, "conversationId": conv}),
                 headers={"Content-Type": "application/json", "Accept": "text/event-stream",
                          "Authorization": "Bearer " + token})
    resp = conn.getresponse()
    out = {"status": resp.status, "ttft_ms": None, "total_ms": None, "answer": "", "outcome": "http"}
    if resp.status != 200:
        conn.close()
        return out
    event, data = None, []
    while True:
        raw = resp.readline()
        if not raw:
            break
        line = raw.decode("utf-8").rstrip("\r\n")
        if line.startswith("event:"):
            event = line[6:].strip()
        elif line.startswith("data:"):
            data.append(line[5:].removeprefix(" "))
        elif line == "":
            if event == "token":
                out["ttft_ms"] = out["ttft_ms"] or round((time.monotonic() - t0) * 1000)
                out["answer"] += "\n".join(data)
            elif event in ("done", "error"):
                out["outcome"] = event
            event, data = None, []
    out["total_ms"] = round((time.monotonic() - t0) * 1000)
    conn.close()
    return out


def tool_calls(base: str, token: str, key: str, timeout: float) -> list:
    url = f"{base}/api/assistant/debug/tool-calls?" + urllib.parse.urlencode({"conversationId": key})
    req = urllib.request.Request(url, headers={"Authorization": "Bearer " + token})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return [c["toolName"] for c in json.load(r)]


def pct(values: list, p: float):
    if not values:
        return None
    s = sorted(values)
    return s[min(len(s) - 1, max(0, math.ceil(p / 100 * len(s)) - 1))]


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--base", help="backend base URL, e.g. http://127.0.0.1:58080")
    ap.add_argument("--token", help="JWT from scripts/p4b/get_test_token.py")
    ap.add_argument("--out", default=f"eval/results/assistant-{dt.date.today().isoformat()}.md")
    ap.add_argument("--questions", default=QUESTIONS)
    ap.add_argument("--timeout", type=float, default=90)
    ap.add_argument("--check-only", action="store_true", help="validate the question set and exit")
    a = ap.parse_args()

    qs = load_questions(a.questions)
    if a.check_only:
        print(f"OK: {len(qs)} questions")
        return 0
    if not a.base or not a.token:
        ap.error("--base and --token are required")
    base, uid = a.base.rstrip("/"), user_id_from_jwt(a.token)

    rows = []
    for q in qs:
        conv = "eval-" + uuid.uuid4().hex[:20]
        r = ask(base, a.token, conv, q["q"], a.timeout)
        called = tool_calls(base, a.token, f"{uid}:{conv}", a.timeout) if r["status"] == 200 else []
        unknown = set(called) - ALLOWED
        if unknown:
            print(f"error: {q['id']} called unknown tools {sorted(unknown)}", file=sys.stderr)
            return 2
        exp, act = set(q["expected_tools"]), set(called)
        rows.append({**q, **r, "called": called, "exact": exp == act,
                     "hit": (not exp and not act) or bool(exp & act)})
        print(f"{q['id']}: status={r['status']} {r['outcome']} ttft={r['ttft_ms']} tools={called}", file=sys.stderr)

    n = len(rows)
    ttft = [r["ttft_ms"] for r in rows if r["ttft_ms"] is not None]
    exact, hit = sum(r["exact"] for r in rows), sum(r["hit"] for r in rows)
    os.makedirs(os.path.dirname(os.path.abspath(a.out)), exist_ok=True)
    with open(a.out, "w", encoding="utf-8") as f:
        f.write(f"# Assistant evaluation {dt.datetime.now(dt.timezone.utc).strftime('%Y-%m-%d %H:%M UTC')}\n\n")
        f.write(f"- backend: `{base}`; questions: {n}\n")
        f.write(f"- exact tool-set match: {exact}/{n} ({exact / n:.0%})\n")
        f.write(f"- at least one expected tool hit: {hit}/{n} ({hit / n:.0%})\n")
        f.write(f"- TTFT p50 / p95: {pct(ttft, 50)} ms / {pct(ttft, 95)} ms ({len(ttft)} streams with a token)\n\n")
        f.write("| id | lang | expected | called | exact | hit | outcome | TTFT ms | total ms |\n")
        f.write("|---|---|---|---|---|---|---|---|---|\n")
        for r in rows:
            f.write(f"| {r['id']} | {r['lang']} | {', '.join(r['expected_tools']) or '-'} | "
                    f"{', '.join(r['called']) or '-'} | {'Y' if r['exact'] else 'N'} | {'Y' if r['hit'] else 'N'} | "
                    f"{r['outcome']} | {r['ttft_ms']} | {r['total_ms']} |\n")
    print(f"wrote {a.out}: exact {exact}/{n}, hit {hit}/{n}, TTFT p50 {pct(ttft, 50)} ms")
    return 0


if __name__ == "__main__":
    sys.exit(main())
