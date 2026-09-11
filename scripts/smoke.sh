#!/usr/bin/env bash
# Post-deploy smoke test — EVAL-O5. Get-or-create -> (fund, optional) -> transfer
# -> replay -> /metrics -> /dashboard, against a real deployed URL.
#
#   ./scripts/smoke.sh https://paytm-wallet.onrender.com
#
# Requires: bash, curl, jq (same as burst.sh) — and psql only if SMOKE_FUND_SQL_URL is set.
#
# Env:
#   SMOKE_TOKEN        bearer token identifying the sender (default: dev-token-alice)
#   SMOKE_TOKEN_B      bearer token identifying the receiver (default: dev-token-bob)
#   SMOKE_FUND_SQL_URL optional: a psql-reachable connection string. If set, the
#                      sender's wallet is funded directly (there is no deposit
#                      API by design — money only enters via a transfer from an
#                      already-funded wallet) so the smoke run exercises a
#                      COMPLETED transfer, not just the decline path.
set -euo pipefail

BASE_URL="${1:?Usage: smoke.sh <deployed-url>}"
TOKEN_A="${SMOKE_TOKEN:-dev-token-alice}"
TOKEN_B="${SMOKE_TOKEN_B:-dev-token-bob}"
USER_A="smoke-a-$$-$(date +%s)"
USER_B="smoke-b-$$-$(date +%s)"

ok()   { printf '  \033[32mok\033[0m  %s\n' "$1"; }
fail() { printf '  \033[31mFAIL\033[0m  %s\n' "$1"; exit 1; }
json() { jq -r ".$1"; }

echo "== target: $BASE_URL =="

echo "== unauthenticated probes =="
code=$(curl -s -o /dev/null -w '%{http_code}' "$BASE_URL/healthz")
[ "$code" = 200 ] && ok "/healthz -> 200" || fail "/healthz -> $code"
code=$(curl -s -o /dev/null -w '%{http_code}' "$BASE_URL/actuator/health")
[ "$code" = 200 ] && ok "/actuator/health -> 200" || fail "/actuator/health -> $code"

echo "== get-or-create =="
wa=$(curl -sf -H "Authorization: Bearer $TOKEN_A" -H 'Content-Type: application/json' \
       -X POST "$BASE_URL/wallets" -d "{\"user_id\":\"$USER_A\"}")
wallet_a=$(echo "$wa" | json id)
wb=$(curl -sf -H "Authorization: Bearer $TOKEN_B" -H 'Content-Type: application/json' \
       -X POST "$BASE_URL/wallets" -d "{\"user_id\":\"$USER_B\"}")
wallet_b=$(echo "$wb" | json id)
ok "wallets A=$wallet_a B=$wallet_b"

if [ -n "${SMOKE_FUND_SQL_URL:-}" ]; then
  psql "$SMOKE_FUND_SQL_URL" -c "UPDATE wallets SET balance_paise = 100000 WHERE id = '$wallet_a'" >/dev/null
  ok "funded A via psql (100000 paise)"
  amount=30000
  expect_status=COMPLETED
else
  echo "  (no SMOKE_FUND_SQL_URL — A has 0 balance, expecting a clean DECLINE)"
  amount=100
  expect_status=DECLINED
fi

echo "== transfer =="
key="smoke-$$-$(date +%s%N)"
body="{\"from\":\"$wallet_a\",\"to\":\"$wallet_b\",\"amount_paise\":$amount,\"idempotency_key\":\"$key\"}"
resp=$(curl -s -w '\n%{http_code}' -H "Authorization: Bearer $TOKEN_A" -H 'Content-Type: application/json' \
         -X POST "$BASE_URL/transfers" -d "$body")
code=$(echo "$resp" | tail -1); resp_body=$(echo "$resp" | sed '$d')
status=$(echo "$resp_body" | json status)
[ "$code" = 201 ] && [ "$status" = "$expect_status" ] && ok "POST /transfers -> 201 $status" \
  || fail "POST /transfers -> $code $resp_body (expected 201 $expect_status)"
transfer_id=$(echo "$resp_body" | json id)

echo "== idempotent replay =="
code=$(curl -s -o /tmp/smoke-replay -w '%{http_code}' -H "Authorization: Bearer $TOKEN_A" \
         -H 'Content-Type: application/json' -X POST "$BASE_URL/transfers" -d "$body")
replay_id=$(json id < /tmp/smoke-replay)
[ "$code" = 200 ] && [ "$replay_id" = "$transfer_id" ] && ok "replay -> 200, same transfer $transfer_id" \
  || fail "replay -> $code $(cat /tmp/smoke-replay)"

echo "== GET /transfers/{id} =="
code=$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $TOKEN_A" "$BASE_URL/transfers/$transfer_id")
[ "$code" = 200 ] && ok "GET /transfers/$transfer_id -> 200" || fail "GET transfer -> $code"

echo "== observability =="
code=$(curl -s -o /tmp/smoke-metrics -w '%{http_code}' "$BASE_URL/metrics")
[ "$code" = 200 ] && grep -q '^wallet_transfers_' /tmp/smoke-metrics && ok "/metrics -> 200, domain counters present" \
  || fail "/metrics -> $code"
code=$(curl -s -o /tmp/smoke-dash -w '%{http_code}' "$BASE_URL/dashboard")
[ "$code" = 200 ] && grep -qi 'paytm-wallet' /tmp/smoke-dash && ok "/dashboard -> 200" || fail "/dashboard -> $code"

echo
echo "smoke test passed against $BASE_URL"
