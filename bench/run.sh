#!/usr/bin/env bash
# Engine benchmark — M threads x mixed transfers for T seconds, once per engine.
# Needs a reachable Postgres:
#   DATABASE_URL      (default jdbc:postgresql://localhost:5432/wallet)
#   DATABASE_USER     (default wallet)
#   DATABASE_PASSWORD (default wallet)
# Tunables: BENCH_THREADS (16), BENCH_SECONDS (10), BENCH_WALLETS (8)
#
#   ./bench/run.sh                       # against local Postgres
#   BENCH_SECONDS=30 ./bench/run.sh
#
# Appends a table to bench/RESULTS.md.
set -euo pipefail
cd "$(dirname "$0")/.."

./mvnw -q -B test-compile exec:java \
  -Dexec.mainClass=com.paytm.wallet.bench.EngineBenchmark \
  -Dexec.classpathScope=test
