#!/usr/bin/env bash
# Summarise virtual-thread pinning reported by -Djdk.tracePinnedThreads=short (docs/phases/P1.md §8, A8).
# Usage: scripts/p1/pinning_report.sh LOG > scripts/db/evidence/p1/pinning.txt
#
# Each report in LOG is a header line followed by indented stack frames; frames that hold a monitor
# end with "<== monitors:N". JDK 21.0.11 prints the header as
#   VirtualThread[#22]/runnable@ForkJoinPool-1-worker-1 reason:MONITOR
# (older builds print "Thread[#22,...]"). Reports are grouped by their top frame (the first frame
# printed). The JDK prints each distinct pinned stack only once per JVM, so counts are distinct
# stacks per top frame, not pinning events.
set -Eeuo pipefail

[[ $# -eq 1 && -f $1 ]] || { echo "usage: $0 LOG" >&2; exit 2; }

python3 - "$1" <<'PY'
import collections, re, sys

log = sys.argv[1]
header = re.compile(r"^(Virtual)?Thread\[#\d+[],]")
reason_re = re.compile(r"reason:(\w+)")
reports = []          # list of frame lists
reasons = collections.Counter()
cur = None
for raw in open(log, errors="replace"):
    line = raw.rstrip("\n")
    if header.match(line):
        cur = []
        reports.append(cur)
        m = reason_re.search(line)
        reasons[m.group(1) if m else "<unspecified>"] += 1
    elif cur is not None and line.startswith((" ", "\t")) and line.strip():
        cur.append(line.strip())
    else:
        cur = None

by_top = collections.Counter()
monitors = collections.Counter()
for frames in reports:
    by_top[frames[0] if frames else "<no frames>"] += 1
    for f in frames:
        if "<== monitors" in f:
            monitors[f] += 1

print(f"# pinning report for {log}")
print(f"# reports (distinct pinned stacks): {len(reports)}")
print("# by reason: " + (", ".join(f"{r}={n}" for r, n in reasons.most_common()) or "-"))
print("\n## by top frame (count\\tframe)")
for frame, n in by_top.most_common():
    print(f"{n}\t{frame}")
print("\n## monitor-holding frames (count\\tframe)")
for frame, n in monitors.most_common():
    print(f"{n}\t{frame}")
if not reports:
    print("(none)")
PY
