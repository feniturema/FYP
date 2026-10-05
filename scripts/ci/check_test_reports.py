#!/usr/bin/env python3
"""Zero-test guard for CI (docs/phases/P6a.md §6.5).

  python3 scripts/ci/check_test_reports.py --expect scripts/ci/expected-tests.json [--root <repo>]

Reads the JUnit XML reports of every module (<root>/*/target/{failsafe,surefire}-reports/TEST-*.xml) and fails
(exit 1) unless:
  - every IT class listed under "failsafe" has a report with tests >= the expected count, and
    failures = errors = skipped = 0;
  - no failsafe report at all (listed or not) has a failure, an error or a skipped test;
  - the Surefire total is >= "surefire_min_total", with failures = errors = skipped = 0.
A missing report, a class that ran zero tests, or a skipped test (@Disabled, assumeTrue) is a failure.
Prints a table: class, tests, failures, errors, skipped. Standard library only.
"""
import argparse
import glob
import json
import os
import sys
import xml.etree.ElementTree as ET

KEYS = ("tests", "failures", "errors", "skipped")


def read_reports(root, kind):
    """Returns {simple class name: {tests, failures, errors, skipped}} summed over modules."""
    out = {}
    for path in sorted(glob.glob(os.path.join(root, "*", "target", f"{kind}-reports", "TEST-*.xml"))):
        suite = ET.parse(path).getroot()
        name = suite.get("name") or os.path.basename(path)[5:-4]
        simple = name.rsplit(".", 1)[-1]
        row = out.setdefault(simple, dict.fromkeys(KEYS, 0))
        for k in KEYS:
            row[k] += int(suite.get(k, 0))
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--expect", required=True, help="expected-tests.json")
    ap.add_argument("--root", default=os.path.normpath(os.path.join(os.path.dirname(__file__), "..", "..")),
                    help="repository root (default: two levels above this script)")
    args = ap.parse_args()

    expect = json.load(open(args.expect))
    min_total = expect.get("surefire_min_total")
    if not isinstance(min_total, int):
        print(f"FAIL surefire_min_total must be an integer (got {min_total!r})")
        return 1

    failsafe = read_reports(args.root, "failsafe")
    surefire = read_reports(args.root, "surefire")
    problems = []

    print("| suite | class | tests | failures | errors | skipped | expected |")
    print("|---|---|---|---|---|---|---|")
    for cls in sorted(set(expect.get("failsafe", {})) | set(failsafe)):
        want = expect.get("failsafe", {}).get(cls)
        row = failsafe.get(cls)
        if row is None:
            print(f"| failsafe | {cls} | - | - | - | - | {want} |")
            problems.append(f"{cls}: no failsafe report (not run)")
            continue
        print(f"| failsafe | {cls} | " + " | ".join(str(row[k]) for k in KEYS) + f" | {want if want is not None else '(not listed)'} |")
        if want is not None and row["tests"] < want:
            problems.append(f"{cls}: {row['tests']} tests < expected {want}")
        if row["tests"] == 0:
            problems.append(f"{cls}: zero tests")
        for k in ("failures", "errors", "skipped"):
            if row[k]:
                problems.append(f"{cls}: {k}={row[k]}")

    total = {k: sum(r[k] for r in surefire.values()) for k in KEYS}
    print(f"| surefire | (all {len(surefire)} classes) | " + " | ".join(str(total[k]) for k in KEYS) + f" | >= {min_total} |")
    if total["tests"] < min_total:
        problems.append(f"surefire: {total['tests']} tests < surefire_min_total {min_total}")
    for k in ("failures", "errors", "skipped"):
        if total[k]:
            problems.append(f"surefire: {k}={total[k]}")

    if problems:
        print("\nFAIL")
        for p in problems:
            print(f"  - {p}")
        return 1
    print(f"\nOK: {len(expect.get('failsafe', {}))} IT classes as expected, surefire {total['tests']} >= {min_total}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
