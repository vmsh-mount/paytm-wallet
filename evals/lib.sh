#!/usr/bin/env bash
# Shared eval/burst helpers. Sourced by scripts/burst.sh and evals/run.sh — not
# meant to be run directly.
#
# Conventions:
#   - all money is integer paise; never bc/floats
#   - FAILURES is a global counter; callers `exit "$FAILURES"` when done
#   - functions run in background (`&`) inherit these definitions — no `export -f`
#     needed, since they stay in the same bash process (never `sh -c`/xargs'd)
#
# API:
#   require_tools NAME...                          exit 127 if any is missing
#   auth_header [alice|bob|USER]                    -> "Authorization: Bearer <token>"
#   http_json METHOD PATH [BODY] [USER]            -> sets HTTP_STATUS, HTTP_BODY
#   new_key PREFIX                                 -> "<prefix>-<pid>-<ns>-<rand>"
#   assert_eq EXPECTED ACTUAL MSG                  -> ok/FAIL, bumps FAILURES
#   assert_ge MIN ACTUAL MSG
#   assert_http EXPECTED_STATUS MSG                -> assert_eq against $HTTP_STATUS
#   sum_balances ID...                             -> echo integer sum of balance_paise
#   run_parallel N MAX_INFLIGHT OUT_DIR FUNC        -> FUNC 1..N, batched, stdout to OUT_DIR/i.out
#   metrics_value SERIES [LABEL_MATCH]              -> scrape /metrics, echo the sample value
#   fund_wallet_if_possible WALLET_ID AMOUNT        -> UPDATE via FUND_SQL_URL if set, else no-op

FAILURES=${FAILURES:-0}

# Color only for a real terminal — a captured log (redirected to a report
# artifact) stays plain text, greppable, and doesn't spam ^[[32m into markdown.
if [ -t 1 ]; then
  C_OK=$'\033[32m'; C_FAIL=$'\033[31m'; C_RESET=$'\033[0m'
else
  C_OK=''; C_FAIL=''; C_RESET=''
fi

require_tools() {
  local missing=()
  for t in "$@"; do
    command -v "$t" >/dev/null 2>&1 || missing+=("$t")
  done
  if [ "${#missing[@]}" -gt 0 ]; then
    echo "missing required tool(s): ${missing[*]}" >&2
    exit 127
  fi
}

auth_header() {
  case "${1:-alice}" in
    alice) echo "Authorization: Bearer ${TOKEN_A:-dev-token-alice}" ;;
    bob)   echo "Authorization: Bearer ${TOKEN_B:-dev-token-bob}" ;;
    c)     echo "Authorization: Bearer ${TOKEN_C:-${TOKEN_A:-dev-token-alice}}" ;;
    *)     echo "Authorization: Bearer $1" ;;
  esac
}

# http_json METHOD PATH [BODY] [USER]  -- sets HTTP_STATUS, HTTP_BODY
http_json() {
  local method="$1" path="$2" body="${3:-}" user="${4:-alice}"
  local hdr resp
  hdr=$(auth_header "$user")
  if [ -n "$body" ]; then
    resp=$(curl -sS -w '\n%{http_code}' -H "$hdr" -H 'Content-Type: application/json' \
                 -X "$method" "$BASE_URL$path" -d "$body")
  else
    resp=$(curl -sS -w '\n%{http_code}' -H "$hdr" -X "$method" "$BASE_URL$path")
  fi
  HTTP_STATUS=$(printf '%s' "$resp" | tail -1)
  HTTP_BODY=$(printf '%s' "$resp" | sed '$d')
}

new_key() {
  echo "${1:-key}-$$-$(date +%s%N)-$RANDOM"
}

assert_eq() {
  local expected="$1" actual="$2" msg="$3"
  if [ "$expected" = "$actual" ]; then
    printf '  %sok%s  %s\n' "$C_OK" "$C_RESET" "$msg"
  else
    printf '  %sFAIL%s  %s (expected %s, got %s)\n' "$C_FAIL" "$C_RESET" "$msg" "$expected" "$actual"
    FAILURES=$((FAILURES + 1))
  fi
}

assert_ge() {
  local min="$1" actual="$2" msg="$3"
  if [ "$actual" -ge "$min" ] 2>/dev/null; then
    printf '  %sok%s  %s\n' "$C_OK" "$C_RESET" "$msg"
  else
    printf '  %sFAIL%s  %s (expected >= %s, got %s)\n' "$C_FAIL" "$C_RESET" "$msg" "$min" "$actual"
    FAILURES=$((FAILURES + 1))
  fi
}

assert_http() {
  assert_eq "$1" "$HTTP_STATUS" "$2"
}

sum_balances() {
  local sum=0 id bal
  for id in "$@"; do
    http_json GET "/wallets/$id"
    bal=$(printf '%s' "$HTTP_BODY" | jq -r '.balance_paise // 0')
    sum=$((sum + bal))
  done
  echo "$sum"
}

# run_parallel N MAX_INFLIGHT OUT_DIR FUNC — FUNC "$i" for i in 1..N, batched by
# MAX_INFLIGHT so a free-tier target isn't hit with N raw simultaneous sockets.
# stdout/stderr of each call land in OUT_DIR/<i>.out / .err.
run_parallel() {
  local n="$1" max="$2" out_dir="$3" func="$4"
  mkdir -p "$out_dir"
  local i=1
  while [ "$i" -le "$n" ]; do
    local end=$((i + max - 1))
    [ "$end" -gt "$n" ] && end=$n
    local j
    for j in $(seq "$i" "$end"); do
      ( "$func" "$j" > "$out_dir/$j.out" 2>"$out_dir/$j.err" ) &
    done
    wait
    i=$((end + 1))
  done
}

# metrics_value NAME [LABEL_REGEX] — scrape /metrics once, echo the first
# matching sample's value (0 if absent). LABEL_REGEX, if given, must also match
# the line (e.g. 'reason="insufficient_funds"').
metrics_value() {
  local name="$1" label="${2:-}"
  local scrape; scrape=$(curl -sS "$BASE_URL/metrics" 2>/dev/null || true)
  local line
  if [ -n "$label" ]; then
    line=$(printf '%s\n' "$scrape" | grep -E "^${name}(\{[^}]*\})? " | grep -F "$label" | head -1)
  else
    line=$(printf '%s\n' "$scrape" | grep -E "^${name}(\{[^}]*\})? " | head -1)
  fi
  local val; val=$(printf '%s' "$line" | awk '{print $NF}')
  [ -n "$val" ] && printf '%s' "$val" || echo 0
}

# fund_wallet_if_possible WALLET_ID AMOUNT_PAISE
# Only wired when FUND_SQL_URL is set (auto-defaulted for a localhost BASE_URL —
# see burst.sh/run.sh). There is no deposit API by design: money only enters via
# a transfer from an already-funded wallet, so demonstrating a COMPLETED
# transfer (rather than just a clean decline) needs this operator-only hook.
fund_wallet_if_possible() {
  local wallet_id="$1" amount="$2"
  if [ -z "${FUND_SQL_URL:-}" ]; then
    return 1
  fi
  psql "$FUND_SQL_URL" -v ON_ERROR_STOP=1 -c \
    "UPDATE wallets SET balance_paise = $amount WHERE id = '$wallet_id'" >/dev/null
}
