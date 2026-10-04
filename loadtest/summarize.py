#!/usr/bin/env python3
"""Validate and summarise the run directories of one config (docs/phases/P0.md §6.7).

  python3 loadtest/summarize.py --dir loadtest/results/_smoke/P0

A run directory is valid only if it has run.json, summary.json and verify.tsv and
run.json.eventId == summary.meta.eventId. Invalid directories are listed and the
script exits 1. Standard library only.
"""
import argparse
import csv
import json
import os
import sys


def load_run(path):
    """Returns (row, None) for a valid run directory, or (None, reason)."""
    files = {n: os.path.join(path, n) for n in ("run.json", "summary.json", "verify.tsv")}
    missing = [n for n, p in files.items() if not os.path.isfile(p)]
    if missing:
        return None, "missing " + ", ".join(missing)
    try:
        run = json.load(open(files["run.json"]))
        meta = json.load(open(files["summary.json"]))["meta"]
        rows = list(csv.DictReader(open(files["verify.tsv"]), delimiter="\t"))
    except (OSError, ValueError, KeyError) as e:
        return None, f"unreadable: {e}"
    if run.get("eventId") is None or run.get("eventId") != meta.get("eventId"):
        return None, f"eventId mismatch: run.json={run.get('eventId')} summary={meta.get('eventId')}"
    if not rows:
        return None, "verify.tsv has no data row"
    v = rows[0]
    results = meta.get("results", {})
    return {
        "runId": run.get("runId"),
        "scenario": run.get("scenario"),
        "eventId": run.get("eventId"),
        "exit": run.get("exit_code"),
        "k6_rc": run.get("k6_rc"),
        "verify_ok": run.get("verify_ok"),
        "accepted": results.get("ACCEPTED"),
        "sold_out": results.get("SOLD_OUT"),
        "already_bought": results.get("ALREADY_BOUGHT"),
        "orders": v.get("orders"),
        "buyers": v.get("buyers"),
        "requestRps": meta.get("requestRps"),
        "acceptedRps": meta.get("acceptedRps"),
    }, None


def fmt(v):
    if isinstance(v, float):
        return f"{v:.2f}"
    return "-" if v is None else str(v)


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--dir", required=True, help="<out-root>/<config>")
    args = ap.parse_args()
    if not os.path.isdir(args.dir):
        sys.exit(f"summarize: {args.dir} is not a directory")

    valid, invalid = [], []
    for name in sorted(os.listdir(args.dir)):
        path = os.path.join(args.dir, name)
        if not os.path.isdir(path):
            continue
        row, reason = load_run(path)
        if row:
            valid.append(row)
        else:
            invalid.append((name, reason))

    cols = ["runId", "scenario", "eventId", "exit", "k6_rc", "verify_ok", "accepted", "sold_out",
            "already_bought", "orders", "buyers", "requestRps", "acceptedRps"]
    print("| " + " | ".join(cols) + " |")
    print("|" + "---|" * len(cols))
    for r in valid:
        print("| " + " | ".join(fmt(r[c]) for c in cols) + " |")
    print(f"\nvalid runs: {len(valid)}; invalid: {len(invalid)}")
    for name, reason in invalid:
        print(f"INVALID {name}: {reason}")
    if invalid or not valid:
        sys.exit(1)


if __name__ == "__main__":
    main()
