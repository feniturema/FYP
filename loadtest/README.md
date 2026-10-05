# Load testing (k6)

Scripts for comparing SecKill behaviour and throughput across upgrade phases.
Introduced in P0 (`docs/phases/P0.md` §6.6–§6.7) and reused by P1–P3; P2 added the outbox
drain mode, `verify_outbox.sql`, `compose:` targets and the 409 default; P3 added the `none` drain
mode (sync), `persist.sql`, the three-throughput summary and the A/B/C benchmark scripts in `bench/`.

| File | Role |
| --- | --- |
| `throughput.js` | Ramp to `RATE` req/s over `RAMP` s, hold for `STEADY` s; every iteration is a new synthetic buyer. Thresholds include `seckill_accept_rate{scenario:steady} >= 0.99` (P3): a steady phase that runs out of stock is invalid |
| `contention.js` | `BUYERS` distinct buyers (`VUS` concurrent) race for `STOCK` units; `REQUEST_LOG=1` prints one `REQ userId=… status=… result=… token=…` line per request (used by P2 A6 / `scripts/p2/verify_crash.py`) |
| `lib/jwt.js` | HS256 tokens signed with the backend's `JWT_SECRET` (no account registration needed) |
| `lib/result.js` | Judges each response by its JSON `result`, counts `seckill_results{result}` and `seckill_mismatch`; `REJECT_STATUS` defaults to **409** (P2+) |
| `setup_event.py` | Creates a fresh product + SecKill event via the admin API and waits until it is `ACTIVE` |
| `drain.sh` | Waits until everything is persisted: `stream` (pre-P2 Redis Stream) or `outbox` (P2+: no NEW outbox row, Kafka group lag 0, orders == outbox rows for the event); `run.sh --drain none` (sync) skips it |
| `verify_legacy.sql` | Pre-P2 runs: counts the run's SECKILL orders by `ref_id` (no `seckill_event_id` before V2) |
| `verify_outbox.sql` | P2+ runs: orders by `seckill_event_id`, plus `sold_count` and the outbox counts |
| `persist.sql` | P3: orders of the event and the span between the first and last `created_at` (→ `persist.tsv`, `persistedRps`); the event column is `ref_id` for `--drain stream`, else `seckill_event_id` |
| `run.sh` | One run = one result directory: setup → k6 → drain → verify → persist → `run.json` |
| `summarize.py` | Validates run directories (P3 §6.3) and prints the three-throughput table; `--configs` for several configs, `--self-test` |
| `reset.sh` | P3: deletes SECKILL orders, outbox rows and the `seckill:stock:*` / `seckill:bought:*` keys between benchmark runs; needs `CONFIRM_RESET=yes` |
| `bench/infra_up.sh`, `bench/infra_down.sh` | P3: start / destroy compose project `ftsm-p3-bench` (MySQL 33306, Redis 36379, Kafka 39092 only) |
| `bench/run_config.sh` | P3: one configuration (A/B/C), one scenario: build, start the backend on the host, warm up, `run.sh`, stop, reset |
| `bench/ENVIRONMENT.md` | P3: environment record to fill in before a formal measurement |

## Running

Prerequisites: k6 2.3.0 (`K6=$(scripts/tools/install_k6.sh)`, pinned sha256), a backend
listening on `--base`, and the MySQL/Redis it uses reachable through a `--mysql`/`--redis`
target: the temporary instances from `scripts/db/lib.sh` (`temp:<db>`, `temp`) or, from P2, a
compose project (`compose:<project>:<env-file>[:<db>]`; `--drain outbox` needs this form because it
reads the Kafka consumer group inside the project's `kafka` service).

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
python3 loadtest/summarize.py --dir loadtest/results/_smoke/P0   # P0-format runs: listed as SKIPPED since P3

stop_backend; stop_temp_redis; stop_temp_mysql; rm -rf "$P0_TMP"
```

`--reject-status` is `200` for the baseline, P0 and P1 (sold out / already bought return
200) and `409` from P2. The k6 scripts default to `409`, so **configs that run pre-P2 code (A /
baseline, P0, P1) must pass `--reject-status 200` (`REJECT_STATUS=200`) explicitly**. `--drain`
picks the code generation and the verification:

| `--drain` | Code | Waits for | Verify | Redis keys | Extra checks |
| --- | --- | --- | --- | --- | --- |
| `stream` | pre-P2 (baseline, P0, P1) | Redis Stream consumer group | `verify_legacy.sql` (`ref_id`) | `seckill:stock:<id>` | — |
| `outbox` | P2+ async (default) | no NEW outbox row, Kafka lag 0 | `verify_outbox.sql` (`seckill_event_id`) | `seckill:stock:{<id>}` | `sold_count == orders` |
| `none` | P2+ sync (`SECKILL_MODE=sync`, P3) | nothing (orders are written before the response) | `verify_outbox.sql` | `seckill:stock:{<id>}` | `sold_count == orders`; **no** outbox row for the event |

P2 example (inside the acceptance project, see `scripts/p2/acceptance.sh`):

```bash
loadtest/run.sh --config P2 --scenario contention --stock 5 --buyers 20 --vus 20 --reject-status 409 \
  --drain outbox --mysql compose:ftsm-p2-acc:$P2_TMP/compose.env:ftsm_ecommerce \
  --redis compose:ftsm-p2-acc:$P2_TMP/compose.env --base http://127.0.0.1:28080 \
  --out-root loadtest/results/_smoke
``` `run.sh` exits with k6's code when thresholds fail (99), 3 when the
drain times out, 4 when `orders == buyers == accepted` (and, for contention, Redis stock 0)
does not hold, and 2 for usage errors or an existing run directory.

Each run directory `<out-root>/<config>/<RUN_ID>/` holds `run.json` (git SHA, parameters,
environment, exit codes; since P3 also `"format": 2`, `backendMode`, `jvmArgs` and `drainSeconds`
in whole seconds),
`summary.json` (`meta` + k6 metrics; P3 adds `acceptRateSteady` and steady p50/p95/p99),
`k6.log`, `verify.tsv`, `persist.tsv` and `redis.txt`. Run directories are never reused; a failed
run keeps its directory and `summarize.py` reports it as invalid.

## Three configurations (P3)

`docs/phases/P3.md` §6.4, §7. All three run on the same host against the `ftsm-p3-bench`
infrastructure, each with its own database, with the same JDK 21 and JVM options
(`-Xms2g -Xmx2g -XX:+UseG1GC`); the backend is a host JVM on port 8080.

| Config | Result dir | Backend | `--reject-status` | `--drain` | Database |
| --- | --- | --- | --- | --- | --- |
| A | `A-baseline` | jar built from tag `v0.4.2-baseline` (in `$BENCH_TMP/baseline`, reused) | 200 | `stream` | `ftsm_bench_a` |
| B | `B-sync` | current jar, `SECKILL_MODE=sync` | 409 | `none` | `ftsm_bench_b` |
| C | `C-async` | current jar, default `async` | 409 | `outbox` | `ftsm_bench_c` |

```bash
loadtest/bench/infra_up.sh        # writes $BENCH_ENV (default /tmp/ftsm-p3-bench/bench.env, random JWT secret)
export K6=$(scripts/tools/install_k6.sh)
for cfg in A B C; do
  loadtest/bench/run_config.sh $cfg contention --stock 5 --buyers 20 --vus 20 --out-root loadtest/results/_smoke
  loadtest/bench/run_config.sh $cfg throughput --rate 20 --ramp 5 --steady 15 --stock 100000 \
    --out-root loadtest/results/_smoke
done
python3 loadtest/summarize.py --dir loadtest/results/_smoke --configs A-baseline,B-sync,C-async
loadtest/bench/infra_down.sh      # down -v of ftsm-p3-bench only, then removes $BENCH_TMP
```

`run_config.sh` builds the jar, starts the backend (exit 2 if it is not healthy within 180 s), runs
one warm-up (`--rate 50 --ramp 5 --steady 10`, written to `<config>/warmup/`, never counted), runs
`run.sh`, stops the backend and finally calls `reset.sh`. The reset happens only if this call
created a run directory, no k6 process is running and that run's drain succeeded: B and C share
Redis and their event ids restart at 1 in each database, so leftover keys would leak into the next
config. Its exit code is `run.sh`'s.

Formal measurement (H1, by a person, P3 §7.3): fill in `bench/ENVIRONMENT.md`; for each config
start at `--rate 1000` and add 500 per step, three runs per step of
`run_config.sh <cfg> throughput --rate R --ramp 60 --steady 120`; stop at the first invalid run,
p99 ≥ 1000 ms or error rate ≥ 1 %; the last step with three valid runs is the sustainable rate.
Contention: three runs per config with `--stock 100 --buyers 5000 --vus 1000`. Then
`python3 loadtest/summarize.py --dir loadtest/results --configs A-baseline,B-sync,C-async > loadtest/results/SUMMARY.md`
(the spec's `--configs A,B,C` means these result directories) and copy the table into the README
"Performance" section in a separate PR (`P3-results: formal benchmark (A/B/C)`).

## Smoke vs. formal measurements

- **Smoke** (`loadtest/results/_smoke/`): a few hundred requests on whatever machine runs
  the phase. It proves the scripts, thresholds and reconciliation work. **Its numbers are
  not performance results** and must not be quoted as such.
- **Formal measurement** (`loadtest/results/<config>/`): done by a person on a dedicated,
  otherwise idle machine (physical or exclusive VM, ≥ 8 cores, ≥ 16 GB) with the same scripts and
  a longer steady phase (P3 H1). Record the environment in `bench/ENVIRONMENT.md` (P3) or with the
  template below.
- **Conclusion discipline**: no throughput, latency or speed-up figure goes into the README, the
  CV or a PR description until H1 is complete with three valid runs per config. Configs with
  fewer than three valid runs are reported as "not completed", never extrapolated. A run that
  `summarize.py` marks invalid is never quoted.

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
