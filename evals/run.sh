#!/usr/bin/env bash
# Eval runner — executes the scenario suite against a target URL and writes a
# timestamped markdown report + raw artifacts under evals/reports/.
#
#   ./evals/run.sh                       # http://localhost:8080
#   ./evals/run.sh https://<deployed-url>
#
# Env: N K ROUNDS MAX_INFLIGHT FUND_SQL_URL TOKEN_A TOKEN_B TOKEN_C  (see scripts/burst.sh)
#
# What this runs:
#   EVAL-C1..C5   -> scripts/burst.sh (probes 1-4 cover C1-C4; the 409 leg of
#                    probe 2 covers C5)
#   EVAL-O5       -> scripts/smoke.sh
#   EVAL-O4       -> /metrics deltas around the burst
#   EVAL-O3       -> `docker compose logs app` excerpt, only when a local
#                    compose stack is reachable on the target's host
#   EVAL-C6       -> only meaningful with multiple engines live and a way to
#                    restart the target per engine; out of scope for a single
#                    black-box run — EngineParityIT (CI) covers it white-box,
#                    bench/RESULTS.md covers it live
#   EVAL-O1, O2   -> container/compose hardening; already asserted by
#                    scripts/verify-container.sh in CI's `container` job — not
#                    re-run here (would mean rebuilding the image)
#
# No associative arrays / `local a=X b=$a` — this targets bash 3.2 (macOS's
# shipped default) as much as bash 4+, so each scenario gets its own plain
# VERDICT_<ID> / NUMBERS_<ID> variable instead.
#
# Exit code = number of failed scenarios (0 = full green).
set -uo pipefail

cd "$(dirname "$0")/.."
# shellcheck source=lib.sh
source evals/lib.sh

require_tools bash curl jq

BASE_URL="${1:-http://localhost:8080}"
BASE_URL="${BASE_URL%/}"
TS=$(date -u +%Y%m%dT%H%M%SZ)
OUT_DIR="evals/reports/$TS"
mkdir -p "$OUT_DIR"

SCENARIO_FAILURES=0
SCENARIO_COUNT=0

mark() { # SCENARIO -> "✅"/"❌"/"⏭️" for a PASS/FAIL/SKIP verdict value
  case "$1" in
    PASS) echo "✅" ;;
    FAIL) echo "❌" ;;
    *)    echo "⏭️" ;;
  esac
}

echo "== evals/run.sh against $BASE_URL =="

echo "-- preflight --"
http_json GET /actuator/health
if [ "$HTTP_STATUS" != 200 ]; then
  echo "target unreachable/unhealthy ($HTTP_STATUS) — aborting" >&2
  exit 1
fi
echo "target healthy."

echo "-- /metrics before --"
BEFORE_CREATED=$(metrics_value wallet_transfers_completed_total)
BEFORE_DECLINED=$(metrics_value wallet_transfers_declined_total)
BEFORE_REPLAY=$(metrics_value wallet_transfers_idempotent_replay_total)
curl -sS "$BASE_URL/metrics" 2>/dev/null | grep -E '^wallet_' > "$OUT_DIR/metrics-before.txt" || true

echo "-- EVAL-C1..C5: scripts/burst.sh --"
BURST_LOG="$OUT_DIR/burst.log"
BASE_URL="$BASE_URL" N="${N:-30}" K="${K:-20}" ROUNDS="${ROUNDS:-100}" \
  MAX_INFLIGHT="${MAX_INFLIGHT:-10}" FUND_SQL_URL="${FUND_SQL_URL:-}" \
  TOKEN_A="${TOKEN_A:-}" TOKEN_B="${TOKEN_B:-}" TOKEN_C="${TOKEN_C:-}" \
  ./scripts/burst.sh "$BASE_URL" > "$BURST_LOG" 2>&1 || true
cat "$BURST_LOG"

probe_fails() { # probe_fails "Probe N:" -> count of FAIL lines within that probe's section
  awk -v p="$1" 'BEGIN{c=0} $0~p{f=1} f && /FAIL/{c++} f && /^== Probe/ && $0!~p{f=0} END{print c}' "$BURST_LOG"
}

c1=$(probe_fails 'Probe 1:'); [ "$c1" -eq 0 ] && VERDICT_C1=PASS || VERDICT_C1=FAIL
NUMBERS_C1="$c1 fail(s) in probe 1"
c2=$(probe_fails 'Probe 2:'); [ "$c2" -eq 0 ] && VERDICT_C2=PASS || VERDICT_C2=FAIL
NUMBERS_C2="$c2 fail(s) in probe 2"
VERDICT_C5=$VERDICT_C2
NUMBERS_C5="409-conflict leg of probe 2"
c3=$(probe_fails 'Probe 3:'); [ "$c3" -eq 0 ] && VERDICT_C3=PASS || VERDICT_C3=FAIL
NUMBERS_C3="$c3 fail(s) in probe 3"
c4=$(probe_fails 'Probe 4:'); [ "$c4" -eq 0 ] && VERDICT_C4=PASS || VERDICT_C4=FAIL
NUMBERS_C4="$c4 fail(s) in probe 4"

for v in "$VERDICT_C1" "$VERDICT_C2" "$VERDICT_C3" "$VERDICT_C4" "$VERDICT_C5"; do
  SCENARIO_COUNT=$((SCENARIO_COUNT + 1))
  [ "$v" = FAIL ] && SCENARIO_FAILURES=$((SCENARIO_FAILURES + 1))
done

VERDICT_C6=SKIP; NUMBERS_C6="needs multiple engines live — see EngineParityIT (CI) + bench/RESULTS.md"
VERDICT_O1=SKIP; NUMBERS_O1="asserted by scripts/verify-container.sh in CI's container job"
VERDICT_O2=SKIP; NUMBERS_O2="asserted by scripts/verify-container.sh in CI's container job"

echo
echo "-- EVAL-O5: scripts/smoke.sh --"
SMOKE_LOG="$OUT_DIR/smoke.log"
if SMOKE_FUND_SQL_URL="${FUND_SQL_URL:-}" ./scripts/smoke.sh "$BASE_URL" > "$SMOKE_LOG" 2>&1; then
  VERDICT_O5=PASS
else
  VERDICT_O5=FAIL
fi
NUMBERS_O5="see smoke.log"
SCENARIO_COUNT=$((SCENARIO_COUNT + 1))
[ "$VERDICT_O5" = FAIL ] && SCENARIO_FAILURES=$((SCENARIO_FAILURES + 1))
cat "$SMOKE_LOG"

echo
echo "-- EVAL-O4: /metrics deltas --"
AFTER_CREATED=$(metrics_value wallet_transfers_completed_total)
AFTER_DECLINED=$(metrics_value wallet_transfers_declined_total)
AFTER_REPLAY=$(metrics_value wallet_transfers_idempotent_replay_total)
curl -sS "$BASE_URL/metrics" 2>/dev/null | grep -E '^wallet_' > "$OUT_DIR/metrics-after.txt" || true
D_CREATED=$(( ${AFTER_CREATED%.*} - ${BEFORE_CREATED%.*} ))
D_DECLINED=$(( ${AFTER_DECLINED%.*} - ${BEFORE_DECLINED%.*} ))
D_REPLAY=$(( ${AFTER_REPLAY%.*} - ${BEFORE_REPLAY%.*} ))
echo "Δ completed=$D_CREATED declined=$D_DECLINED replay=$D_REPLAY"
SCENARIO_COUNT=$((SCENARIO_COUNT + 1))
# Counters must move and replay must be seen either way; completed only has to
# move when this run could actually fund a COMPLETED transfer (FUND_SQL_URL
# set) — against a deployed URL with no DB access it's expected to stay 0
# while declined does the moving (documented, not a failure).
if [ -n "${FUND_SQL_URL:-}" ]; then
  [ "$D_CREATED" -ge 1 ] && [ "$D_REPLAY" -ge 1 ] && VERDICT_O4=PASS || VERDICT_O4=FAIL
else
  [ "$D_DECLINED" -ge 1 ] && [ "$D_REPLAY" -ge 1 ] && VERDICT_O4=PASS || VERDICT_O4=FAIL
fi
[ "$VERDICT_O4" = FAIL ] && SCENARIO_FAILURES=$((SCENARIO_FAILURES + 1))
NUMBERS_O4="Δcompleted=$D_CREATED Δdeclined=$D_DECLINED Δreplay=$D_REPLAY"

echo
echo "-- EVAL-O3: structured logs --"
LOG_EXCERPT="$OUT_DIR/log-excerpt.txt"
VERDICT_O3=SKIP
case "$BASE_URL" in
  *localhost*|*127.0.0.1*)
    if command -v docker >/dev/null 2>&1 && docker compose ps app >/dev/null 2>&1; then
      docker compose logs app --no-color 2>/dev/null | tail -500 > "$LOG_EXCERPT"
      errors=$(grep -c '"level":"ERROR"' "$LOG_EXCERPT" || true)
      secret_hits=$(grep -cE 'Bearer (dev-token-alice|dev-token-bob)' "$LOG_EXCERPT" || true)
      SCENARIO_COUNT=$((SCENARIO_COUNT + 1))
      if [ "$errors" -eq 0 ] && [ "$secret_hits" -eq 0 ]; then
        VERDICT_O3=PASS
        NUMBERS_O3="0 ERROR lines, 0 raw tokens in $(wc -l < "$LOG_EXCERPT" | tr -d ' ') lines"
      else
        VERDICT_O3=FAIL
        SCENARIO_FAILURES=$((SCENARIO_FAILURES + 1))
        NUMBERS_O3="$errors ERROR line(s), $secret_hits raw-token hit(s)"
      fi
    else
      NUMBERS_O3="no local 'docker compose' app service found"
    fi
    ;;
  *) NUMBERS_O3="target isn't local — grab the Render/Loki log link manually (see README)" ;;
esac

# --------------------------------------------------------------------------
{
cat <<EOF
# Eval report — $TS

| | |
|---|---|
| **Target** | \`$BASE_URL\` |
| **Commit** | \`$(git rev-parse --short HEAD 2>/dev/null || echo unknown)\` |
| **Params** | \`N=${N:-30} K=${K:-20} ROUNDS=${ROUNDS:-100} MAX_INFLIGHT=${MAX_INFLIGHT:-10}\` |
| **Funded via psql** | $([ -n "${FUND_SQL_URL:-}" ] && echo yes || echo "no (decline-path only)") |

## Results matrix

| Scenario | Invariant / requirement | Verdict | Numbers |
|----------|--------------------------|---------|---------|
| EVAL-C1 | #4 race-free get-or-create | $(mark "$VERDICT_C1") | $NUMBERS_C1 |
| EVAL-C2 | #3 exactly-once (retry storm) | $(mark "$VERDICT_C2") | $NUMBERS_C2 |
| EVAL-C3 | #1 conservation under contention | $(mark "$VERDICT_C3") | $NUMBERS_C3 |
| EVAL-C4 | #2 no-overdraft race | $(mark "$VERDICT_C4") | $NUMBERS_C4 |
| EVAL-C5 | #3 conflict → 409 | $(mark "$VERDICT_C5") | $NUMBERS_C5 |
| EVAL-C6 | engine parity | $(mark "$VERDICT_C6") | $NUMBERS_C6 |
| EVAL-O1 | container hardening | $(mark "$VERDICT_O1") | $NUMBERS_O1 |
| EVAL-O2 | one-command compose | $(mark "$VERDICT_O2") | $NUMBERS_O2 |
| EVAL-O3 | structured logs + correlation id | $(mark "$VERDICT_O3") | ${NUMBERS_O3:-} |
| EVAL-O4 | metrics + domain counters | $(mark "$VERDICT_O4") | $NUMBERS_O4 |
| EVAL-O5 | deployed smoke | $(mark "$VERDICT_O5") | $NUMBERS_O5 |

Legend: ✅ pass · ❌ fail · ⏭️ skipped (see Numbers column for why)

## /metrics delta

\`\`\`
wallet_transfers_completed_total          +$D_CREATED
wallet_transfers_declined_total           +$D_DECLINED
wallet_transfers_idempotent_replay_total  +$D_REPLAY
\`\`\`

## Artifacts

- \`$OUT_DIR/burst.log\` — full \`scripts/burst.sh\` transcript
- \`$OUT_DIR/smoke.log\` — full \`scripts/smoke.sh\` transcript
- \`$OUT_DIR/metrics-before.txt\`, \`metrics-after.txt\` — \`/metrics\` scrapes, filtered to \`wallet_*\` lines
EOF
[ -f "$LOG_EXCERPT" ] && echo "- \`$LOG_EXCERPT\` — last 500 app log lines"
cat <<EOF

## Verdict

$((SCENARIO_COUNT - SCENARIO_FAILURES))/$SCENARIO_COUNT scored scenarios passing (C6/O1/O2 intentionally skipped — see above). Exit code \`$SCENARIO_FAILURES\`.
EOF
} > "evals/reports/$TS.md"

echo
echo "== report: evals/reports/$TS.md =="
exit "$SCENARIO_FAILURES"
