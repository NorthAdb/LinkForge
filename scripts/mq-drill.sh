#!/usr/bin/env bash
# MQ outage drill (P2 acceptance c):
#   1. healthy baseline: N redirects converge in stats (live MQ path)
#   2. stop RabbitMQ     -> M redirects all still 302 (publish failures degrade, sync log keeps data)
#   3. start RabbitMQ    -> stats converge to N+M via the reconciliation loop
# Requires: compose stack up, app running with P2 defaults (async on, sync on).
# Env: BASE_URL (default http://localhost:8080), N (default 15), M (default 10).

set -u
cd "$(dirname "$0")/.."

BASE_URL="${BASE_URL:-http://localhost:8080}"
N="${N:-15}"
M="${M:-10}"

fail() { echo "DRILL FAIL: $1"; exit 1; }
json_str() { grep -o "\"$1\":\"[^\"]*\"" | head -1 | sed "s/\"$1\":\"//;s/\"$//"; }
total_of() { curl -s -H "Authorization: Bearer $1" "$BASE_URL/api/links/$2/stats" \
  | grep -o '"totalClicks":[0-9]*' | head -1 | cut -d: -f2; }

RABBIT_OK=0
restore_rabbit() {
  if [ "$RABBIT_OK" = "0" ]; then
    docker compose start rabbitmq >/dev/null 2>&1
    echo "(rabbitmq restarted by cleanup)"
  fi
}
trap restore_rabbit EXIT

# 0. fresh tenant + link
REG=$(curl -s -X POST "$BASE_URL/api/auth/register" -H 'Content-Type: application/json' \
  -d "{\"tenantName\":\"drill-$(date +%s)\",\"username\":\"driller\",\"password\":\"drill-pass-123\"}")
TOKEN=$(echo "$REG" | json_str token)
[ -n "$TOKEN" ] || fail "register failed: $REG"
CODE=$(curl -s -X POST "$BASE_URL/api/links" -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"originalUrl":"https://example.com/drill"}' | json_str code)
[ -n "$CODE" ] || fail "create link failed"
echo "setup: code=$CODE user=driller"

# 1. baseline: N redirects through live MQ
for i in $(seq 1 "$N"); do curl -s -o /dev/null "$BASE_URL/r/$CODE"; done
for i in $(seq 1 60); do
  T=$(total_of "$TOKEN" "$CODE"); [ "${T:-0}" = "$N" ] && break; sleep 2
done
[ "${T:-x}" = "$N" ] || fail "baseline did not converge to $N (got ${T:-none})"
echo "PASS baseline: $N clicks aggregated via live MQ"

# 2. outage: stop rabbit, M redirects must still 302
docker compose stop rabbitmq >/dev/null 2>&1 || fail "could not stop rabbitmq"
echo "rabbitmq stopped"
sleep 2
for i in $(seq 1 "$M"); do
  S=$(curl -s -o /dev/null -w '%{http_code}' "$BASE_URL/r/$CODE")
  [ "$S" = "302" ] || fail "redirect during outage returned $S (expected 302)"
done
echo "PASS outage: $M redirects stayed 302 with MQ down"

# 3. recovery: start rabbit, stats must reach N+M
docker compose start rabbitmq >/dev/null 2>&1 || fail "could not start rabbitmq"
RABBIT_OK=1
for i in $(seq 1 30); do
  H=$(docker inspect -f '{{.State.Health.Status}}' linkforge-rabbitmq 2>/dev/null)
  [ "$H" = "healthy" ] && break; sleep 3
done
[ "${H:-x}" = "healthy" ] || fail "rabbitmq never became healthy again"
echo "rabbitmq healthy again"
for i in $(seq 1 90); do
  T=$(total_of "$TOKEN" "$CODE"); [ "${T:-0}" = "$((N+M))" ] && break; sleep 2
done
if [ "${T:-x}" != "$((N+M))" ]; then
  echo "current total: ${T:-none} (expected $((N+M)))"
  fail "stats did not converge after MQ recovery"
fi
echo "PASS recovery: stats converged to $((N+M)) (reconcile caught up)"
echo "DRILL OK"
