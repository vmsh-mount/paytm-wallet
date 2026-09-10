#!/usr/bin/env bash
# One-command burst script — reproduces the three live probes against a deployed URL.
#
#   ./scripts/burst.sh https://your-app.onrender.com
#
# Requires: bash, curl, jq. Uses only background curl + wait for concurrency.
set -euo pipefail

BASE_URL="${1:-http://localhost:8080}"
TOKEN_A="${TOKEN_A:-dev-token-alice}"
TOKEN_B="${TOKEN_B:-dev-token-bob}"
N="${N:-50}"          # concurrency for get-or-create
K="${K:-30}"          # concurrency for idempotent retry storm
ROUNDS="${ROUNDS:-200}" # concurrent transfers for conservation test

echo "== target: $BASE_URL =="

# TODO(scaffold): implement the three probes below.
#
# 1. Concurrent get-or-create   (spec: evals/scenarios/EVAL-C1-concurrent-get-or-create.md)
#    - pick a brand-new user id
#    - fire N POST /wallets in parallel
#    - assert every response has the same wallet id  -> exactly one wallet
#
# 2. Idempotent retry storm
#    - create wallets A (funded) and B
#    - generate ONE idempotency_key
#    - fire K POST /transfers with the identical body in parallel
#    - assert: exactly one COMPLETED debit occurred (B gained amount once),
#      all K responses identical, only one transfers row
#    - then replay same key with a different amount -> expect 409
#
# 3. Conservation under contention
#    - create wallets A, B, C with known balances; record TOTAL
#    - fire ROUNDS transfers in parallel, mixing A->B, B->A, B->C, C->A, random small amounts
#    - wait; then assert sum(balances) == TOTAL and every balance >= 0
#    - print declined-insufficient-funds count

echo "scaffold: burst probes not implemented yet"
