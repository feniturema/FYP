#!/usr/bin/env python3
"""Check the human labels in eval/queries.jsonl against the demo catalogue (docs/phases/P5a.md §6.7). Standard library.

    python3 eval/check_labels.py                     # check labels; exit 0 only when all 80 queries pass
    python3 eval/check_labels.py --base origin/main  # also require that only "relevant" changed vs that git ref
    python3 eval/check_labels.py --checklist         # print a Markdown labelling checklist (no answers) and exit

Checks: 80 queries, 20 per type, unique ids; 1-3 refs per query, no duplicates; every ref exists in
backend/src/main/resources/demo/catalog.json as product:<id> or item:<id>; for "constraint" queries every ref
satisfies maxPrice (inclusive), category and type. It never writes or suggests labels.
Exit codes: 0 all checks pass; 1 some check failed; 2 a file could not be read.
"""
import argparse
import collections
import json
import re
import subprocess
import sys

QUERIES = "eval/queries.jsonl"
CATALOG = "backend/src/main/resources/demo/catalog.json"
TYPES = ("lexical", "synonym", "crosslingual", "constraint")
REF = re.compile(r"(product|item):\d+")


def parse_lines(text):
    return [json.loads(line) for line in text.splitlines() if line.strip() and not line.lstrip().startswith("#")]


def load_catalog(path):
    with open(path, encoding="utf-8") as f:
        cat = json.load(f)
    rows = {f"product:{p['id']}": ("product", p) for p in cat["products"]}
    rows.update({f"item:{i['id']}": ("item", i) for i in cat["items"]})
    return rows


def check_set(qs):
    errs = []
    if len(qs) != 80:
        errs.append(f"{len(qs)} queries, expected 80")
    counts = collections.Counter(q.get("type") for q in qs)
    errs += [f"type {t}: {counts.get(t, 0)} queries, expected 20" for t in TYPES if counts.get(t, 0) != 20]
    dup = [i for i, n in collections.Counter(q.get("id") for q in qs).items() if n > 1]
    if dup:
        errs.append(f"duplicate ids: {', '.join(dup)}")
    return errs


def check_labels(qs, rows):
    errs = []
    for q in qs:
        rel = q.get("relevant") or []
        if not 1 <= len(rel) <= 3:
            errs.append(f"{q['id']}: {len(rel)} refs, need 1-3")
            continue
        if len(set(rel)) != len(rel):
            errs.append(f"{q['id']}: duplicate refs")
        f = q.get("filters") or {}
        for ref in rel:
            if not isinstance(ref, str) or not REF.fullmatch(ref) or ref not in rows:
                errs.append(f"{q['id']}: {ref!r} is not a product:<id> / item:<id> in catalog.json")
                continue
            kind, row = rows[ref]
            if "maxPrice" in f and row["price"] > f["maxPrice"]:
                errs.append(f"{q['id']}: {ref} price {row['price']} > maxPrice {f['maxPrice']}")
            if "category" in f and row["category"] != f["category"]:
                errs.append(f"{q['id']}: {ref} category {row['category']} != {f['category']}")
            if f.get("type") in ("product", "item") and kind != f["type"]:
                errs.append(f"{q['id']}: {ref} is a {kind}, filter type is {f['type']}")
    return errs


def check_only_relevant_changed(qs, base):
    try:
        old_text = subprocess.run(["git", "show", f"{base}:{QUERIES}"], capture_output=True, text=True,
                                  check=True).stdout
    except (subprocess.CalledProcessError, FileNotFoundError) as e:
        return [f"cannot read {QUERIES} at {base}: {e}"]
    old = {q["id"]: q for q in parse_lines(old_text)}
    errs = []
    if set(old) != {q["id"] for q in qs}:
        errs.append(f"query ids differ from {base}")
    for q in qs:
        o = old.get(q["id"])
        if o is None:
            continue
        changed = [k for k in sorted(set(o) | set(q)) if k != "relevant" and o.get(k) != q.get(k)]
        if changed:
            errs.append(f"{q['id']}: {', '.join(changed)} changed vs {base} (only relevant may change)")
    return errs


def checklist(qs):
    out = ["# Labelling checklist (fill `relevant` in eval/queries.jsonl; 1-3 refs each)", ""]
    for t in TYPES:
        group = [q for q in qs if q.get("type") == t]
        out += [f"## {t} ({len(group)})", "", "| done | id | q | filters | relevant |", "|---|---|---|---|---|"]
        for q in group:
            f = json.dumps(q["filters"], ensure_ascii=False) if q.get("filters") else ""
            done = "x" if q.get("relevant") else " "
            out.append(f"| [{done}] | {q['id']} | {q['q']} | {f} | {', '.join(q.get('relevant') or [])} |")
        out.append("")
    return "\n".join(out)


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--base", help="git ref to compare against; only 'relevant' may differ")
    ap.add_argument("--checklist", action="store_true", help="print a Markdown checklist and exit")
    a = ap.parse_args()
    try:
        with open(QUERIES, encoding="utf-8") as f:
            qs = parse_lines(f.read())
        rows = load_catalog(CATALOG)
    except (OSError, json.JSONDecodeError) as e:
        print(f"cannot read input: {e}", file=sys.stderr)
        return 2
    if a.checklist:
        print(checklist(qs))
        return 0
    errs = check_set(qs) + check_labels(qs, rows)
    if a.base:
        errs += check_only_relevant_changed(qs, a.base)
    if errs:
        print("\n".join(errs))
        print(f"FAIL: {len(errs)} problems; labelled {sum(bool(q.get('relevant')) for q in qs)}/{len(qs)}",
              file=sys.stderr)
        return 1
    print(f"OK: {len(qs)} queries, {sum(len(q['relevant']) for q in qs)} refs")
    return 0


if __name__ == "__main__":
    sys.exit(main())
