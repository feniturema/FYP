# Load testing (k6)

Scripts for comparing SecKill behaviour and throughput across upgrade phases.
Introduced in P0 (`docs/phases/P0.md` §6.6–§6.7) and reused by P1–P3.

| File | Role |
| --- | --- |
| `throughput.js` | Ramp to `RATE` req/s over `RAMP` s, hold for `STEADY` s; every iteration is a new synthetic buyer |
| `contention.js` | `BUYERS` distinct buyers (`VUS` concurrent) race for `STOCK` units |
| `lib/jwt.js` | HS256 tokens signed with the backend's `JWT_SECRET` (no account registration needed) |
| `lib/result.js` | Judges each response by its JSON `result`, counts `seckill_results{result}` and `seckill_mismatch` |
| `setup_event.py` | Creates a fresh product + SecKill event via the admin API and waits until it is `ACTIVE` |
| `drain.sh` | Waits until the stream consumer has persisted everything (`stream` mode; `outbox` arrives in P2) |
| `verify.sql` | Counts the run's SECKILL orders by `ref_id` (the baseline schema has no `seckill_event_id`) |
| `run.sh` | One run = one result directory: setup → k6 → drain → verify → `run.json` |
| `summarize.py` | Validates every run directory of one config and prints a summary table |

## Running

Prerequisites: k6 2.3.0 (`K6=$(scripts/tools/install_k6.sh)`, pinned sha256), a backend
listening on `--base`, and the MySQL/Redis it uses reachable through a `--mysql`/`--redis`
target. P0 supports the temporary instances from `scripts/db/lib.sh` (`temp:<db>`, `temp`).

```bash
export P0_TMP=$(mktemp -d /tmp/ftsm-p0.XXXXXX) P0_MYSQL_MODE=local DB_PASSWORD=root
export JWT_SECRET=p0-loadtest-secret-0123456789abcdef0123
source scripts/db/lib.sh
start_temp_mysql && start_temp_redis
start_backend backend/target/ecommerce-0.0.1-SNAPSHOT.jar ftsm_p0_smoke 8080 "$P0_TMP/backend.log"
export K6=$(scripts/tools/install_k6.sh)

loadtest/run.sh --config P0 --scenario contention --stock 5 --buyers 20 --vus 20 \
  --reject-status 200 --drain stream --mysql temp:ftsm_p0_smoke --redis temp \
  --out-root loadtest/results/_smoke
loadtest/run.sh --config P0 --scenario throughput --stock 100000 --rate 10 --ramp 10 --steady 20 \
  --reject-status 200 --drain stream --mysql temp:ftsm_p0_smoke --redis temp \
  --out-root loadtest/results/_smoke
python3 loadtest/summarize.py --dir loadtest/results/_smoke/P0

stop_backend; stop_temp_redis; stop_temp_mysql; rm -rf "$P0_TMP"
```

`--reject-status` is `200` for the baseline, P0 and P1 (sold out / already bought return
200) and `409` from P2. `run.sh` exits with k6's code when thresholds fail (99), 3 when the
drain times out, 4 when `orders == buyers == accepted` (and, for contention, Redis stock 0)
does not hold, and 2 for usage errors or an existing run directory.

Each run directory `<out-root>/<config>/<RUN_ID>/` holds `run.json` (git SHA, parameters,
environment, exit codes), `summary.json` (`meta` + k6 metrics), `k6.log`, `verify.tsv` and
`redis.txt`. Run directories are never reused.

## Smoke vs. formal measurements

- **Smoke** (`loadtest/results/_smoke/`): a few hundred requests on whatever machine runs
  the phase. It proves the scripts, thresholds and reconciliation work. **Its numbers are
  not performance results** and must not be quoted as such.
- **Formal baseline** (`loadtest/results/<config>/`): done by a person on a dedicated,
  otherwise idle machine (physical or exclusive VM, ≥ 8 cores) before P3, with the same
  scripts and a longer steady phase. Record the environment with the template below.

Steady-state QPS is always `count / STEADY_SECONDS` (`meta.requestRps`, `meta.acceptedRps`);
k6's submetric `rate` divides by the whole test duration and is not used.

### Environment record template

```text
Date / operator:
Machine (model, CPU, cores, RAM, disk), virtualised? exclusive?:
OS / kernel:
JDK (vendor, version), JVM flags:
MySQL version, mode (docker/local), config changes:
Redis version, persistence settings:
Backend config (git SHA / tag, profile, pool sizes):
k6 version, load generator location (same host?):
Other load on the host during the run:
Commands run (exact):
```
