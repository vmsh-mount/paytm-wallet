#!/usr/bin/env bash
# One-command burst script — reproduces the correctness probes against ANY
# running instance (local compose or deployed) as a black box: HTTP only, no
# DB access required for probes 1 and (partially) 4.
#
#   ./scripts/burst.sh                       # http://localhost:8080
#   ./scripts/burst.sh https://your-app.onrender.com
#
# Requires: bash, curl, jq.
#
# Env (all optional):
#   TOKEN_A / TOKEN_B / TOKEN_C   bearer tokens (default: the dev tokens; C unset -> C-leg skipped)
#   N        concurrency for probe 1 (get-or-create)              default 30
#   K        concurrency for probe 2 (idempotent retry storm)     default 20
#   ROUNDS   concurrent transfers for probe 3 (conservation)      default 100
#   MAX_INFLIGHT  cap on simultaneous in-flight requests          default 10
#   FUND_SQL_URL  psql-reachable connection string used to fund wallets directly
#                 (there is no deposit API by design). Auto-defaults to the
#                 compose Postgres (127.0.0.1:5432) when BASE_URL is localhost;
#                 required for a real deployed URL if you want probes 2-4 to
#                 exercise a COMPLETED transfer rather than the decline path.
#
# Exit code = number of failed assertions across all probes (0 = all green).
set -uo pipefail  # not -e: assertions must keep running after a mismatch

cd "$(dirname "$0")/.."
# shellcheck source=../evals/lib.sh
source evals/lib.sh

require_tools bash curl jq

BASE_URL="${1:-http://localhost:8080}"
BASE_URL="${BASE_URL%/}"
N="${N:-30}"
K="${K:-20}"
ROUNDS="${ROUNDS:-100}"
MAX_INFLIGHT="${MAX_INFLIGHT:-10}"

case "$BASE_URL" in
  *localhost*|*127.0.0.1*)
    FUND_SQL_URL="${FUND_SQL_URL:-postgresql://wallet:wallet@localhost:5432/wallet}"
    ;;
  *) FUND_SQL_URL="${FUND_SQL_URL:-}" ;;
esac

WORKDIR=$(mktemp -d)
trap 'rm -rf "$WORKDIR"' EXIT

echo "== target: $BASE_URL =="
if [ -n "$FUND_SQL_URL" ]; then echo "== funding via: psql (direct DB access) =="
else echo "== no FUND_SQL_URL — probes 2-4 will exercise the DECLINE path only (documented, not a failure) =="
fi
echo

# ---- shared setup: two stable, ownable wallets (userId == the token's mapped user) ----
http_json POST /wallets "{\"user_id\":\"alice\"}" alice
WALLET_A=$(printf '%s' "$HTTP_BODY" | jq -r .id)
http_json POST /wallets "{\"user_id\":\"bob\"}" bob
WALLET_B=$(printf '%s' "$HTTP_BODY" | jq -r .id)
WALLET_C=""
if [ -n "${TOKEN_C:-}" ]; then
  http_json POST /wallets "{\"user_id\":\"${USER_C:-carol}\"}" c
  WALLET_C=$(printf '%s' "$HTTP_BODY" | jq -r .id)
fi
echo "wallets: A=$WALLET_A B=$WALLET_B${WALLET_C:+ C=$WALLET_C}"
echo

# =========================================================================
# Probe 1 — concurrent get-or-create (#4)   spec: EVAL-C1
# =========================================================================
probe1() {
  echo "== Probe 1: concurrent get-or-create (N=$N) =="
  local user; user=$(new_key "burst-p1")
  p1_worker() { http_json POST /wallets "{\"user_id\":\"$user\"}"; echo "$HTTP_STATUS"; echo "$HTTP_BODY"; }
  run_parallel "$N" "$MAX_INFLIGHT" "$WORKDIR/p1" p1_worker

  local bad=0 ids=() f
  for f in "$WORKDIR"/p1/*.out; do
    [ "$(sed -n '1p' "$f")" = 200 ] || bad=$((bad + 1))
    ids+=("$(sed -n '2p' "$f" | jq -r .id)")
  done
  assert_eq 0 "$bad" "all $N concurrent creates returned 200"
  local uniq; uniq=$(printf '%s\n' "${ids[@]}" | sort -u | wc -l | tr -d ' ')
  assert_eq 1 "$uniq" "exactly one distinct wallet id across $N concurrent creates"
  echo
}

# =========================================================================
# Probe 2 — idempotent retry storm (#3)   spec: EVAL-C2, EVAL-C5
# =========================================================================
probe2() {
  echo "== Probe 2: idempotent retry storm (K=$K) =="
  local amount=1000
  fund_wallet_if_possible "$WALLET_A" 1000000 && amount=50000
  local before; before=$(sum_balances "$WALLET_B")
  local key; key=$(new_key "burst-p2")
  local body="{\"from\":\"$WALLET_A\",\"to\":\"$WALLET_B\",\"amount_paise\":$amount,\"idempotency_key\":\"$key\"}"

  p2_worker() { http_json POST /transfers "$body" alice; echo "$HTTP_STATUS"; echo "$HTTP_BODY"; }
  run_parallel "$K" "$MAX_INFLIGHT" "$WORKDIR/p2" p2_worker

  local ids=() statuses=() codes=() f
  for f in "$WORKDIR"/p2/*.out; do
    codes+=("$(sed -n '1p' "$f")")
    ids+=("$(sed -n '2p' "$f" | jq -r .id)")
    statuses+=("$(sed -n '2p' "$f" | jq -r .status)")
  done
  local uniq_ids; uniq_ids=$(printf '%s\n' "${ids[@]}" | sort -u | wc -l | tr -d ' ')
  assert_eq 1 "$uniq_ids" "all $K identical requests resolved to the same transfer"
  local ones; ones=$(printf '%s\n' "${codes[@]}" | grep -c '^201$' || true)
  assert_eq 1 "$ones" "exactly one 201 (fresh) among $K concurrent identical requests"
  local twohundreds; twohundreds=$(printf '%s\n' "${codes[@]}" | grep -c '^200$' || true)
  assert_eq $((K - 1)) "$twohundreds" "the other $((K - 1)) were 200 (idempotent replay)"
  local uniq_status; uniq_status=$(printf '%s\n' "${statuses[@]}" | sort -u | wc -l | tr -d ' ')
  assert_eq 1 "$uniq_status" "identical status across all $K responses"

  local after; after=$(sum_balances "$WALLET_B")
  if [ "${statuses[0]}" = "COMPLETED" ]; then
    assert_eq "$((before + amount))" "$after" "B credited exactly once (not $K times)"
  else
    assert_eq "$before" "$after" "declined replay moved no money"
  fi

  # same key, different amount -> 409, no extra movement
  local conflict_body="{\"from\":\"$WALLET_A\",\"to\":\"$WALLET_B\",\"amount_paise\":$((amount + 1)),\"idempotency_key\":\"$key\"}"
  http_json POST /transfers "$conflict_body" alice
  assert_http 409 "same key, different body -> 409"
  local after2; after2=$(sum_balances "$WALLET_B")
  assert_eq "$after" "$after2" "the 409 attempt moved no money"
  echo
}

# =========================================================================
# Probe 3 — conservation under contention (#1, + deadlock-freedom)  spec: EVAL-C3
# =========================================================================
probe3() {
  echo "== Probe 3: conservation under contention (ROUNDS=$ROUNDS) =="
  local each=500000
  fund_wallet_if_possible "$WALLET_A" "$each"
  fund_wallet_if_possible "$WALLET_B" "$each"
  local wallets=("$WALLET_A" "$WALLET_B") users=(alice bob)
  if [ -n "$WALLET_C" ]; then
    fund_wallet_if_possible "$WALLET_C" "$each"
    wallets+=("$WALLET_C"); users+=(c)
  fi
  local total_before; total_before=$(sum_balances "${wallets[@]}")

  p3_worker() {
    local i="$1" nlegs=${#wallets[@]}
    # separate `local` statements: bash expands every RHS of a single
    # `local a=X b=Y` before assigning either, so `Y` can't see `a` yet —
    # under `set -u` that reads as "fi: unbound variable" here.
    local fi=$((RANDOM % nlegs))
    local ti=$(((fi + 1 + RANDOM % (nlegs - 1)) % nlegs))
    [ "$nlegs" -eq 1 ] && ti=$fi
    local amt=$((1 + RANDOM % 999))
    local body="{\"from\":\"${wallets[$fi]}\",\"to\":\"${wallets[$ti]}\",\"amount_paise\":$amt,\"idempotency_key\":\"burst-p3-$i-$$-$(date +%s%N)\"}"
    http_json POST /transfers "$body" "${users[$fi]}"
    echo "$HTTP_STATUS"
  }
  run_parallel "$ROUNDS" "$MAX_INFLIGHT" "$WORKDIR/p3" p3_worker

  local bad=0 f
  for f in "$WORKDIR"/p3/*.out; do
    st=$(sed -n '1p' "$f")
    case "$st" in 200|201) : ;; *) bad=$((bad + 1)) ;; esac
  done
  assert_eq 0 "$bad" "no unexpected status among $ROUNDS concurrent transfers (no 5xx/403/404)"

  local total_after; total_after=$(sum_balances "${wallets[@]}")
  assert_eq "$total_before" "$total_after" "Σ balances unchanged after $ROUNDS concurrent transfers"
  local min_bal=999999999999
  local w
  for w in "${wallets[@]}"; do
    http_json GET "/wallets/$w"
    local b; b=$(printf '%s' "$HTTP_BODY" | jq -r .balance_paise)
    [ "$b" -lt "$min_bal" ] && min_bal=$b
  done
  assert_ge 0 "$min_bal" "no wallet balance went negative"
  echo
}

# =========================================================================
# Probe 4 — no-overdraft race (#2)   spec: EVAL-C4
# =========================================================================
probe4() {
  echo "== Probe 4: no-overdraft race =="
  local funded=0 amount=100 attempts=25
  if fund_wallet_if_possible "$WALLET_A" 500; then funded=1; fi
  local before_a; before_a=$(sum_balances "$WALLET_A")
  local before_b; before_b=$(sum_balances "$WALLET_B")
  local expect_completed=$((before_a / amount))
  [ "$expect_completed" -gt "$attempts" ] && expect_completed=$attempts

  p4_worker() {
    local i="$1"
    http_json POST /transfers "{\"from\":\"$WALLET_A\",\"to\":\"$WALLET_B\",\"amount_paise\":$amount,\"idempotency_key\":\"burst-p4-$i-$$-$(date +%s%N)\"}" alice
    printf '%s' "$HTTP_BODY" | jq -r .status
  }
  run_parallel "$attempts" "$MAX_INFLIGHT" "$WORKDIR/p4" p4_worker

  local completed=0 declined=0 f st
  for f in "$WORKDIR"/p4/*.out; do
    st=$(sed -n '1p' "$f")
    [ "$st" = COMPLETED ] && completed=$((completed + 1))
    [ "$st" = DECLINED ] && declined=$((declined + 1))
  done
  assert_eq "$expect_completed" "$completed" "exactly floor(balance/amount)=$expect_completed of $attempts completed"
  assert_eq $((attempts - expect_completed)) "$declined" "the rest ($((attempts - expect_completed))) declined cleanly"

  local after_a; after_a=$(sum_balances "$WALLET_A")
  assert_ge 0 "$after_a" "source balance never went negative"
  if [ "$funded" -eq 1 ]; then
    assert_eq "$((before_a - expect_completed * amount))" "$after_a" "source debited exactly $expect_completed times, no more"
  fi
  echo
}

probe1
probe2
probe3
probe4

echo "== summary: $FAILURES assertion failure(s) =="
exit "$FAILURES"
