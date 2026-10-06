#!/usr/bin/env python3
"""Retrieval evaluation for GET /api/search (docs/phases/P5a.md §6.7, acceptance A7). Standard library only.

    python3 eval/run_retrieval_eval.py --base http://127.0.0.1:63080 --modes keyword \\
        --pricing eval/pricing.json --out eval/results/retrieval-$(date +%F).md

Sends every query with q, limit, mode, debug=true and all of its filters (maxPrice, category, type). The backend
must run with the dev or test profile, otherwise mode/debug are ignored. Metrics per mode, overall and per query type:
  Recall@5  mean over queries of |relevant ∩ top5| / |relevant|
  MRR@10    mean of 1 / rank of the first relevant hit in the top 10 (0 if none)
  p50 ms    median client latency of successful requests
  $/1k q    (embeddingTokens·embedding + rerankIn·chat input + rerankOut·chat output prices) / 1e6
            / successful queries × 1000
A failed request (non-200 or timeout) scores 0 for Recall and MRR and counts as a failure.
Exit codes: 0 ok; 2 failure rate above 5 % in any mode; 3 some queries have an empty "relevant" (unlabelled).
Lines starting with '#' in the queries file are comments.
"""
import argparse
import json
import statistics
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

FILTER_KEYS = ("maxPrice", "category", "type")
MAX_FAILURE_RATE = 0.05


def load(path):
    with open(path, encoding="utf-8") as f:
        return [json.loads(line) for line in f if line.strip() and not line.lstrip().startswith("#")]


def ask(base, q, mode, limit, timeout):
    params = {"q": q["q"], "limit": limit, "mode": mode, "debug": "true"}
    for k in FILTER_KEYS:
        if k in q.get("filters", {}):
            params[k] = q["filters"][k]
    url = f"{base}/api/search?" + urllib.parse.urlencode(params)
    t0 = time.monotonic()
    try:
        with urllib.request.urlopen(url, timeout=timeout) as r:
            body = json.load(r)
            status = r.status
    except urllib.error.HTTPError as e:
        return {"ok": False, "error": f"HTTP {e.code}"}
    except (urllib.error.URLError, TimeoutError, OSError, ValueError) as e:
        return {"ok": False, "error": type(e).__name__}
    ms = (time.monotonic() - t0) * 1000
    if status != 200:
        return {"ok": False, "error": f"HTTP {status}"}
    usage = (body.get("debug") or {}).get("usage") or {}
    return {"ok": True, "ms": ms, "refs": [h["ref"] for h in body.get("hits", [])], "usage": usage}


def score(relevant, refs):
    rel = set(relevant)
    recall5 = len(rel & set(refs[:5])) / len(rel)
    mrr = next((1 / (i + 1) for i, r in enumerate(refs[:10]) if r in rel), 0.0)
    return recall5, mrr


def cost_per_1k(rows, pricing):
    ok = [r for r in rows if r["ok"]]
    if not ok or not pricing:
        return None
    total = sum(r["usage"].get("embeddingTokens", 0) * pricing["embedding_per_1m_tokens"]
                + r["usage"].get("rerankInputTokens", 0) * pricing["chat_input_per_1m_tokens"]
                + r["usage"].get("rerankOutputTokens", 0) * pricing["chat_output_per_1m_tokens"] for r in ok)
    return total / 1e6 / len(ok) * 1000


def summarise(rows, pricing):
    ok_ms = [r["ms"] for r in rows if r["ok"]]
    cost = cost_per_1k(rows, pricing)
    return {
        "n": len(rows),
        "recall5": statistics.fmean(r["recall5"] for r in rows) if rows else 0.0,
        "mrr10": statistics.fmean(r["mrr"] for r in rows) if rows else 0.0,
        "p50": statistics.median(ok_ms) if ok_ms else None,
        "failures": sum(not r["ok"] for r in rows),
        "cost": cost,
    }


def table(title, groups):
    lines = [f"### {title}", "", "| group | n | Recall@5 | MRR@10 | p50 ms | failures | $/1k queries |",
             "|---|---|---|---|---|---|---|"]
    for name, s in groups:
        p50 = "-" if s["p50"] is None else f"{s['p50']:.0f}"
        cost = "-" if s["cost"] is None else f"{s['cost']:.4f}"
        lines.append(f"| {name} | {s['n']} | {s['recall5']:.3f} | {s['mrr10']:.3f} | {p50} | {s['failures']} | {cost} |")
    return "\n".join(lines) + "\n"


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--base", required=True)
    ap.add_argument("--queries", default="eval/queries.jsonl")
    ap.add_argument("--modes", default="keyword", help="comma-separated: keyword,vector,rrf,rrf_rerank")
    ap.add_argument("--pricing", help="eval/pricing.json, filled in on the day of the run")
    ap.add_argument("--out", help="write the Markdown report here as well")
    ap.add_argument("--timeout", type=float, default=10)
    ap.add_argument("--limit", type=int, default=10)
    a = ap.parse_args()

    queries = load(a.queries)
    unlabelled = [q["id"] for q in queries if not q.get("relevant")]
    if unlabelled:
        print(f"{len(unlabelled)} queries have no relevant labels yet: {', '.join(unlabelled)}", file=sys.stderr)
        return 3
    pricing = json.load(open(a.pricing)) if a.pricing else None
    base = a.base.rstrip("/")
    modes = [m.strip() for m in a.modes.split(",") if m.strip()]

    report = [f"# Retrieval evaluation\n\n- base: `{base}`; queries: `{a.queries}` ({len(queries)}); "
              f"limit {a.limit}; pricing: {pricing.get('date') + ' ' + pricing.get('source', '') if pricing else 'none'}\n"]
    worst_failure_rate = 0.0
    for mode in modes:
        rows = []
        for q in queries:
            r = ask(base, q, mode, a.limit, a.timeout)
            r["recall5"], r["mrr"] = score(q["relevant"], r["refs"]) if r["ok"] else (0.0, 0.0)
            r["type"] = q.get("type", "?")
            rows.append(r)
        overall = summarise(rows, pricing)
        worst_failure_rate = max(worst_failure_rate, overall["failures"] / len(rows))
        by_type = [(t, summarise([r for r in rows if r["type"] == t], pricing))
                   for t in sorted({r["type"] for r in rows})]
        report.append(table(f"mode {mode}: overall", [("all", overall)]))
        report.append(table(f"mode {mode}: by query type", by_type))

    text = "\n".join(report)
    print(text)
    if a.out:
        with open(a.out, "w", encoding="utf-8") as f:
            f.write(text)
    if worst_failure_rate > MAX_FAILURE_RATE:
        print(f"failure rate {worst_failure_rate:.1%} is above {MAX_FAILURE_RATE:.0%}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
