#!/usr/bin/env bash
# A7 outbox drill: sync write OFF, broker outage mid-drill, catch-up after recovery.
#   1. boots its own app instance (default port 8082) with LINKFORGE_SYNC_CLICK_ENABLED=false
#   2. stops RabbitMQ -> M redirects must all stay 302 and park as PENDING in lf_outbox
#   3. starts RabbitMQ -> relay delivers; aggregate, consumer-written detail and UV converge
# Proves the pre-A7 story ("sync off + MQ down = clicks lost") is gone.
# Requires: compose stack up, built jar (target/linkforge-1.0.0.jar), .env.
# Env: M (default 10), APP_PORT (default 8082).
set -u
cd "$(dirname "$0")/.."

M="${M:-10}"
APP_PORT="${APP_PORT:-8082}"
BASE_URL="http://localhost:$APP_PORT"
JAR="target/linkforge-1.0.0.jar"

fail() { echo "OUTBOX DRILL FAIL: $1"; exit 1; }
json_str() { grep -o "\"$1\":\"[^\"]*\"" | head -1 | sed "s/\"$1\":\"//;s/\"$//"; }
sql() { docker exec linkforge-mysql mysql -ulinkforge -p"$MYSQL_PASSWORD" linkforge -N -B -e "$1" 2>/dev/null; }
total_of() { curl -s -H "Authorization: Bearer $1" "$BASE_URL/api/links/$2/stats" \
  | grep -o '"totalClicks":[0-9]*' | head -1 | cut -d: -f2; }

[ -f .env ] || fail "no .env (cp .env.example .env)"
# export: this script launches the app itself, so .env must win over any stale
# JWT_SECRET and friends living in the invoking shell's environment
set -a
. ./.env
set +a
: "${MYSQL_PASSWORD:?set MYSQL_PASSWORD in .env}"
[ -f "$JAR" ] || fail "jar missing: mvn -B -DskipTests package first"
docker compose ps --status running 2>/dev/null | grep -q mysql || fail "compose mysql not running"

RABBIT_OK=0
APP_PID=""
cleanup() {
  if [ -n "$APP_PID" ]; then kill "$APP_PID" 2>/dev/null; fi
  if [ "$RABBIT_OK" = "0" ]; then
    docker compose start rabbitmq >/dev/null 2>&1
    echo "(rabbitmq restarted by cleanup)"
  fi
}
trap cleanup EXIT

# 0. dedicated app instance: sync write off, outbox+relay on
LINKFORGE_SYNC_CLICK_ENABLED=false SERVER_PORT=$APP_PORT \
  java -jar "$JAR" > /tmp/linkforge-outbox-drill.log 2>&1 &
APP_PID=$!
UP=""
for i in $(seq 1 45); do UP=$(curl -s -m 2 "$BASE_URL/api/health" 2>/dev/null); [ -n "$UP" ] && break; sleep 2; done
[ -n "$UP" ] || { tail -5 /tmp/linkforge-outbox-drill.log; fail "app instance did not come up on $APP_PORT"; }
echo "app instance up on $APP_PORT (sync-click off, outbox+relay on)"

REG=$(curl -s -X POST "$BASE_URL/api/auth/register" -H 'Content-Type: application/json' \
  -d "{\"tenantName\":\"obox-$(date +%s)\",\"username\":\"oboxer\",\"password\":\"obox-pass-123\"}")
TOKEN=$(echo "$REG" | json_str token)
[ -n "$TOKEN" ] || fail "register failed: $REG"
CODE=$(curl -s -X POST "$BASE_URL/api/links" -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"originalUrl":"https://example.com/obox"}' | json_str code)
[ -n "$CODE" ] || fail "create link failed"
echo "setup: code=$CODE"

# 1. outage: rabbit down, M redirects stay 302 and park in the outbox
docker compose stop rabbitmq >/dev/null 2>&1 || fail "could not stop rabbitmq"
echo "rabbitmq stopped"
sleep 2
for i in $(seq 1 "$M"); do
  S=$(curl -s -o /dev/null -w '%{http_code}' "$BASE_URL/r/$CODE")
  [ "$S" = "302" ] || fail "redirect during outage returned $S (expected 302)"
done
P=$(sql "SELECT COUNT(*) FROM lf_outbox WHERE status='PENDING' AND JSON_UNQUOTE(JSON_EXTRACT(payload,'\$.code'))='$CODE'")
[ "${P:-x}" = "$M" ] || fail "outbox PENDING=$P expected $M (sync off must still park every click)"
D=$(sql "SELECT COUNT(*) FROM lf_click_log WHERE code='$CODE'")
[ "${D:-x}" = "0" ] || fail "sync off wrote $D hot-path detail rows (expected 0)"
A=0
for i in $(seq 1 10); do
  A=$(sql "SELECT COALESCE(MAX(attempts),0) FROM lf_outbox WHERE JSON_UNQUOTE(JSON_EXTRACT(payload,'\$.code'))='$CODE'")
  [ "${A:-0}" -ge 1 ] 2>/dev/null && break
  sleep 2
done
[ "${A:-0}" -ge 1 ] 2>/dev/null || fail "relay retry accounting missing: attempts=$A (expected >=1 while broker is down)"
DEFERRED=$(grep -c "outbox relay deferred" /tmp/linkforge-outbox-drill.log)
[ "${DEFERRED:-0}" -ge 1 ] || fail "no relay deferral log lines during outage"
echo "PASS outage: $M redirects stayed 302, outbox PENDING=$M, hot-path detail=0, relay attempts=$A (deferred log lines: $DEFERRED)"

# 2. recovery: relay delivers; aggregate + consumer-written detail + UV converge
docker compose start rabbitmq >/dev/null 2>&1 || fail "could not start rabbitmq"
RABBIT_OK=1
T=""
for i in $(seq 1 90); do T=$(total_of "$TOKEN" "$CODE"); [ "${T:-0}" = "$M" ] && break; sleep 2; done
[ "${T:-x}" = "$M" ] || fail "aggregate did not converge to $M (got ${T:-none})"
D=""
for i in $(seq 1 30); do D=$(sql "SELECT COUNT(*) FROM lf_click_log WHERE code='$CODE'"); [ "${D:-0}" = "$M" ] && break; sleep 2; done
[ "${D:-x}" = "$M" ] || fail "consumer-written detail did not converge to $M (got ${D:-none})"
S=$(sql "SELECT COUNT(*) FROM lf_outbox WHERE status='SENT' AND JSON_UNQUOTE(JSON_EXTRACT(payload,'\$.code'))='$CODE'")
[ "${S:-x}" = "$M" ] || fail "outbox SENT=$S expected $M"
UV=$(sql "SELECT COUNT(DISTINCT ip) FROM lf_click_log WHERE code='$CODE'")
[ "${UV:-0}" -ge 1 ] || fail "UV query returned 0 after convergence"
echo "PASS recovery: aggregate=$M, consumer-written detail=$M, outbox SENT=$M, UV=$UV"
echo "OUTBOX DRILL OK"
