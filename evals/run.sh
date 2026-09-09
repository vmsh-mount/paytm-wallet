#!/usr/bin/env bash
# Eval runner — executes every scenario in evals/scenarios/ against a target URL
# and writes a timestamped markdown report to evals/reports/.
#
#   ./evals/run.sh http://localhost:8080
#   ./evals/run.sh https://<deployed-url>
#
# Env: N K ROUNDS MAX_INFLIGHT ENGINES  (see evals/README.md)
#
# TODO(TASK-12): implement. Planned shape:
#   - source evals/lib.sh (assert_eq, assert_http, sum_balances, parallel_curl)
#   - preflight: require bash>=4, curl, jq; check target /actuator/health
#   - run correctness scenarios: EVAL-C1..C6  (C6 only when ENGINES set + local)
#   - run operational scenarios: EVAL-O1..O5  (O1/O2 local/CI only)
#   - per scenario: capture request/response transcripts, /metrics delta,
#     a correlated log excerpt, final balance dump -> evals/reports/<ts>/<id>.*
#   - emit evals/reports/<ts>.md with a PASS/FAIL matrix + links to artifacts
#   - exit code = number of failed scenarios
set -euo pipefail
BASE_URL="${1:-http://localhost:8080}"
echo "scaffold: evals/run.sh not implemented (TASK-12). target=$BASE_URL"
exit 0
