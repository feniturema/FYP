#!/usr/bin/env python3
"""Validate load-test run directories and print the three-throughput table (docs/phases/P3.md §6.3).

  python3 loadtest/summarize.py --dir loadtest/results/_smoke --configs A-baseline,B-sync,C-async
  python3 loadtest/summarize.py --dir loadtest/results/_smoke/P0            # one config directory
  python3 loadtest/summarize.py --self-test

Metrics (the only accepted definitions):
  requestRps    steady-phase http_reqs / STEADY_SECONDS            (summary.json meta, throughput only)
  acceptedRps   steady-phase ACCEPTED / STEADY_SECONDS             (409/200 rejections excluded)
  persistedRps  orders of the event / (max(created_at) - min(created_at))   (persist.tsv)
  drainSeconds  time drain.sh needed after k6                       (run.json)
  p50/p95/p99   http_req_duration of the steady phase (throughput) or of the whole run (contention), ms

A run is VALID only if (P3 §6.3/§6.4): it has run.json, summary.json, verify.tsv and persist.tsv;
run.json.eventId == summary.meta.eventId; k6 exited 0 (thresholds incl. dropped_iterations{steady}==0,
UNPARSEABLE==0 and, for throughput, seckill_accept_rate{steady}>=0.99); drain succeeded;
verify.orders == meta.accepted; for --drain none (sync) the event has no outbox row.
Directories named `warmup` are skipped (warm-up runs never count). Run directories written before P3
(run.json without "format": 2, e.g. the P0 smoke runs) are listed as "pre-P3 format, not evaluated".
Exit 1 if any evaluated run is invalid or a config has no valid run. Standard library only.
"""
import argparse
import csv
import json
import os
import shutil
import sys
import tempfile

REQUIRED = ("run.json", "summary.json", "verify.tsv", "persist.tsv")


def read_tsv_row(path):
    rows = list(csv.DictReader(open(path), delimiter="\t"))
    return rows[0] if rows else None


def num(v):
    try:
        return float(v)
    except (TypeError, ValueError):
        return None


def latency(summary, scenario):
    metrics = summary.get("metrics", {})
    key = "http_req_duration{scenario:steady}" if scenario == "throughput" else "http_req_duration"
    v = (metrics.get(key) or {}).get("values", {})
    return v.get("med"), v.get("p(95)"), v.get("p(99)")


def load_run(path):
    """Returns ("valid", row) | ("invalid", reason) | ("legacy", reason)."""
    run_json = os.path.join(path, "run.json")
    try:
        run = json.load(open(run_json)) if os.path.isfile(run_json) else None
    except (OSError, ValueError) as e:
        return "invalid", f"run.json unreadable: {e}"
    if run is not None and run.get("format") != 2:
        return "legacy", "pre-P3 format (no persist.tsv / drainSeconds), not evaluated"
    missing = [n for n in REQUIRED if not os.path.isfile(os.path.join(path, n))]
    if missing:
        return "invalid", "missing " + ", ".join(missing)
    try:
        summary = json.load(open(os.path.join(path, "summary.json")))
        meta = summary["meta"]
        verify = read_tsv_row(os.path.join(path, "verify.tsv"))
        persist = read_tsv_row(os.path.join(path, "persist.tsv"))
    except (OSError, ValueError, KeyError) as e:
        return "invalid", f"unreadable: {e}"
    problems = []
    if run.get("eventId") is None or run.get("eventId") != meta.get("eventId"):
        problems.append(f"eventId mismatch run.json={run.get('eventId')} summary={meta.get('eventId')}")
    if run.get("k6_rc") != 0:
        problems.append(f"k6 exit {run.get('k6_rc')} (a threshold failed)")
    if run.get("drain_rc") != 0:
        problems.append(f"drain exit {run.get('drain_rc')}")
    if not verify or not persist:
        problems.append("verify.tsv or persist.tsv has no data row")
    else:
        if num(verify.get("orders")) != num(meta.get("accepted")):
            problems.append(f"verify.orders={verify.get('orders')} != meta.accepted={meta.get('accepted')}")
        if run.get("params", {}).get("drain") == "none" and num(verify.get("outbox_total")) not in (0.0,):
            problems.append(f"sync run has outbox rows: {verify.get('outbox_total')}")
    if run.get("verify_ok") is not True:
        problems.append(f"run.sh verification failed: {run.get('verify_detail')}")
    if problems:
        return "invalid", "; ".join(problems)
    orders, span = num(persist.get("orders")), num(persist.get("span_s"))
    p50, p95, p99 = latency(summary, run.get("scenario"))
    return "valid", {
        "runId": run.get("runId"), "scenario": run.get("scenario"), "mode": run.get("backendMode"),
        "eventId": run.get("eventId"), "accepted": meta.get("accepted"), "orders": persist.get("orders"),
        "requestRps": meta.get("requestRps"), "acceptedRps": meta.get("acceptedRps"),
        "persistedRps": (orders / span) if orders and span else None,
        "drainSeconds": run.get("drainSeconds"), "p50": p50, "p95": p95, "p99": p99,
    }


def fmt(v):
    if isinstance(v, float):
        return f"{v:.2f}"
    return "-" if v is None else str(v)


COLS = ["config", "runId", "scenario", "mode", "eventId", "accepted", "orders", "requestRps",
        "acceptedRps", "persistedRps", "drainSeconds", "p50", "p95", "p99"]


def summarize(root, configs, out):
    config_dirs = [(c, os.path.join(root, c)) for c in configs] if configs else [(os.path.basename(root), root)]
    valid, invalid, legacy, failed_configs = [], [], [], []
    for config, cdir in config_dirs:
        if not os.path.isdir(cdir):
            invalid.append((config, "-", "config directory missing"))
            failed_configs.append(config)
            continue
        n_valid = 0
        for name in sorted(os.listdir(cdir)):
            path = os.path.join(cdir, name)
            if not os.path.isdir(path) or name == "warmup":
                continue
            status, info = load_run(path)
            if status == "valid":
                valid.append(dict(info, config=config))
                n_valid += 1
            elif status == "legacy":
                legacy.append((config, name, info))
            else:
                invalid.append((config, name, info))
        if n_valid == 0:
            failed_configs.append(config)
    print("| " + " | ".join(COLS) + " |", file=out)
    print("|" + "---|" * len(COLS), file=out)
    for r in valid:
        print("| " + " | ".join(fmt(r.get(c)) for c in COLS) + " |", file=out)
    print("\nlatency in ms; requestRps/acceptedRps only exist for throughput runs (steady phase).", file=out)
    print(f"valid runs: {len(valid)}; invalid: {len(invalid)}; pre-P3 format (not evaluated): {len(legacy)}", file=out)
    for config, name, reason in invalid:
        print(f"INVALID {config}/{name}: {reason}", file=out)
    for config, name, reason in legacy:
        print(f"SKIPPED {config}/{name}: {reason}", file=out)
    for config in failed_configs:
        print(f"NO VALID RUN for config {config}", file=out)
    return 1 if invalid or failed_configs else 0


def self_test():
    """A P3-format run directory without verify.tsv must make summarize exit 1 (P3 §8)."""
    tmp = tempfile.mkdtemp(prefix="summarize-selftest-")
    try:
        run = os.path.join(tmp, "X", "20260101T000000Z-X-contention")
        os.makedirs(run)
        json.dump({"format": 2, "runId": "r", "scenario": "contention", "eventId": 1, "k6_rc": 0, "drain_rc": 0,
                   "verify_ok": True, "params": {"drain": "outbox"}}, open(os.path.join(run, "run.json"), "w"))
        json.dump({"meta": {"eventId": 1, "accepted": 5}, "metrics": {}}, open(os.path.join(run, "summary.json"), "w"))
        open(os.path.join(run, "persist.tsv"), "w").write("orders\tspan_s\n5\t0.5\n")
        with open(os.devnull, "w") as devnull:
            rc_missing = summarize(tmp, ["X"], devnull)
        open(os.path.join(run, "verify.tsv"), "w").write(
            "orders\tbuyers\tsold_count\toutbox_new\toutbox_total\n5\t5\t5\t0\t5\n")
        with open(os.devnull, "w") as devnull:
            rc_complete = summarize(tmp, ["X"], devnull)
    finally:
        shutil.rmtree(tmp)
    ok = rc_missing == 1 and rc_complete == 0
    print(f"self-test: missing verify.tsv -> exit {rc_missing} (want 1); complete run -> exit {rc_complete} (want 0): "
          f"{'PASS' if ok else 'FAIL'}")
    return 0 if ok else 1


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--dir", help="<out-root> with --configs, or one <out-root>/<config> directory")
    ap.add_argument("--configs", help="comma-separated config directories under --dir, e.g. A-baseline,B-sync,C-async")
    ap.add_argument("--format", choices=["markdown"], default="markdown")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    if args.self_test:
        return self_test()
    if not args.dir or not os.path.isdir(args.dir):
        ap.error(f"--dir must be an existing directory (got {args.dir})")
    configs = [c.strip() for c in args.configs.split(",") if c.strip()] if args.configs else None
    return summarize(args.dir, configs, sys.stdout)


if __name__ == "__main__":
    sys.exit(main())
