#!/usr/bin/env bash
# Shared eval/burst helpers. Sourced by scripts/burst.sh and evals/run.sh.
#
# TODO(TASK-12): implement. Planned API:
#
#   http_json METHOD PATH [BODY] [--header ...]   -> sets $HTTP_STATUS, $HTTP_BODY
#   auth_header [USER]                            -> "Authorization: Bearer <token>"
#   parallel_curl COUNT MAX_INFLIGHT CMD...       -> run CMD COUNT times, bounded
#   assert_eq EXPECTED ACTUAL MSG                 -> increment $FAILURES on mismatch
#   assert_http EXPECTED_STATUS MSG
#   sum_balances ID...                           -> echo integer sum of GET /wallets/{id}.balance_paise
#   new_key PREFIX                               -> "<prefix>-<uuid>"
#   metrics_value SERIES                         -> scrape /metrics, echo a gauge/counter value
#   require_tools bash curl jq
#
# Conventions:
#   - all money is integer paise; never use bc/floats
#   - FAILURES is a global counter; callers `exit $FAILURES`
set -euo pipefail
echo "scaffold: evals/lib.sh has no runnable body yet (TASK-12)" >&2
