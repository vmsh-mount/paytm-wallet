#!/usr/bin/env bash
# EVAL-O1 (hardening) + EVAL-O2 (one-command compose), as shell assertions.
# Usage: ./scripts/verify-container.sh          (builds, brings up, checks, tears down)
set -euo pipefail
cd "$(dirname "$0")/.."

ok()   { printf '  \033[32mok\033[0m  %s\n' "$1"; }
fail() { printf '  \033[31mFAIL\033[0m  %s\n' "$1"; exit 1; }
trap 'docker compose down -v --remove-orphans >/dev/null 2>&1 || true' EXIT

echo "== build + up (one command) =="
docker compose up -d --build

echo "== EVAL-O1: image hardening =="
img=$(docker compose images -q app | head -1)
docker image inspect "$img" >/dev/null || img=$(docker inspect -f '{{.Image}}' "$(docker compose ps -q app)")

user=$(docker image inspect -f '{{.Config.User}}' "$img")
[ "$user" = "app" ] && ok "runs as non-root user '$user'" || fail "process user is '$user' (want 'app')"

hc=$(docker image inspect -f '{{.Config.Healthcheck.Test}}' "$img")
[ -n "$hc" ] && [ "$hc" != "<nil>" ] && ok "HEALTHCHECK present: $hc" || fail "no HEALTHCHECK"

layers=$(docker image inspect -f '{{len .RootFS.Layers}}' "$img")
ok "$layers image layers"
size=$(docker image inspect -f '{{.Size}}' "$img")
mb=$((size / 1000000))
[ "$mb" -le 300 ] && ok "image size ${mb} MB (<= 300)" || fail "image size ${mb} MB (> 300)"

echo "== EVAL-O2: healthy within 60s, migrations applied, smoke works =="
for i in $(seq 1 60); do
  state=$(docker inspect -f '{{.State.Health.Status}}' "$(docker compose ps -q app)" 2>/dev/null || echo starting)
  [ "$state" = "healthy" ] && break
  sleep 1
done
[ "$state" = "healthy" ] && ok "app healthy in ~${i}s" || { docker compose logs app | tail -40; fail "app not healthy after 60s"; }

docker compose logs app 2>&1 | grep -q "Successfully applied .* migration" && ok "Flyway migration applied on boot" || fail "no Flyway migration log line"

code=$(curl -s -o /tmp/vc-body -w '%{http_code}' -X POST localhost:8080/wallets \
  -H 'Authorization: Bearer dev-token-alice' -H 'Content-Type: application/json' -d '{"user_id":"smoke"}')
[ "$code" = "200" ] && grep -q '"balance_paise":0' /tmp/vc-body && ok "POST /wallets -> 200 $(cat /tmp/vc-body)" || fail "smoke POST /wallets got $code $(cat /tmp/vc-body)"

echo "== readiness tracks the DB =="
started_at=$(docker inspect -f '{{.State.StartedAt}}' "$(docker compose ps -q app)")
docker compose stop db >/dev/null
sleep 8
rc=$(curl -s -o /dev/null -w '%{http_code}' localhost:8080/actuator/health/readiness)
[ "$rc" = "503" ] && ok "db down -> readiness 503" || fail "db down but readiness returned $rc"
live=$(curl -s -o /dev/null -w '%{http_code}' localhost:8080/actuator/health/liveness)
[ "$live" = "200" ] && ok "liveness still 200 (no restart trigger)" || fail "liveness returned $live"

docker compose start db >/dev/null
for i in $(seq 1 30); do
  rc=$(curl -s -o /dev/null -w '%{http_code}' localhost:8080/actuator/health/readiness)
  [ "$rc" = "200" ] && break
  sleep 2
done
[ "$rc" = "200" ] && ok "db restored -> readiness 200 (recovered in ~$((i*2))s)" || fail "readiness stuck at $rc after db restore"
now_started=$(docker inspect -f '{{.State.StartedAt}}' "$(docker compose ps -q app)")
[ "$started_at" = "$now_started" ] && ok "app container never restarted" || fail "app restarted ($started_at -> $now_started)"

echo "== down -v leaves no volume =="
before=$(docker volume ls -q | wc -l | tr -d ' ')
docker compose down -v >/dev/null
trap - EXIT
after=$(docker volume ls -q | wc -l | tr -d ' ')
[ "$after" -le "$before" ] && ok "no dangling volume after 'down -v'" || fail "volume leaked ($before -> $after)"

echo
echo "all container checks passed"
