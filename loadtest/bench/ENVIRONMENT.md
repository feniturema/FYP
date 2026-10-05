# Benchmark environment record (P3 §7.3)

Fill this in **before** a formal measurement and commit it with the results
(`loadtest/results/{A-baseline,B-sync,C-async}/` and `loadtest/results/SUMMARY.md`). A result without a
complete record is not quotable. Smoke runs (`loadtest/results/_smoke/`) do not need one and are never
performance data.

| Item | Value |
| --- | --- |
| Date, operator | |
| Machine model; physical or VM; exclusive during the run? | |
| CPU model, physical cores / threads | |
| Memory (GB), swap | |
| Disk (type, free space) | |
| OS, kernel (`uname -a`) | |
| Docker Engine / Compose versions; Docker CPU and memory limits (`docker info`) | |
| JDK (`java -version`); JVM options (all configs: `-Xms2g -Xmx2g -XX:+UseG1GC`) | |
| Image digests: `mysql:8.0.46`, `redis:8.10.2`, `apache/kafka:3.9.2` (`docker image inspect --format '{{index .RepoDigests 0}}'`) | |
| k6 version and how it runs (binary or `grafana/k6:2.3.0` image) | |
| k6 on the same machine as the backend and infrastructure? (if yes, say so here) | |
| Git SHA of the tree under test (B, C); baseline tag `v0.4.2-baseline` (A) | |
| Other load on the machine during the runs | |
| Commands run (exact, in order) | |
| Deviations from `docs/phases/P3.md` §7.3 | |

Rules (P3 §6.3, §7.3): a run is valid only if k6 exits 0 (no dropped steady iterations, no unparseable
responses, steady accept rate ≥ 0.99), the drain succeeds and verify agrees; three valid runs per rate
step; stop raising the rate at the first invalid run, p99 ≥ 1000 ms or error rate ≥ 1 %.
