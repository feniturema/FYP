#!/usr/bin/env bash
# Start the benchmark infrastructure: MySQL 33306, Redis 36379, Kafka 39092 in compose project
# ftsm-p3-bench (no backend container; backends run on the host, see run_config.sh). P3 §7.1.
set -Eeuo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
bench_env_init
bench_dc up -d mysql redis kafka
bench_healthy mysql 180
bench_healthy kafka 180
bench_healthy redis 60
echo "infra_up: $BENCH_PROJECT ready (BENCH_TMP=$BENCH_TMP BENCH_ENV=$BENCH_ENV)" >&2
