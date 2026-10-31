#!/usr/bin/env bash
# D-C4-14..19 fault drill: the app degrades instead of dying.
#   1. redis down   -> redirects still 302 (L2 fail-open), creates still 200
#                      (idempotency fail-open), self-heals when redis returns
#   2. webhook 5xx  -> delivery retries 3x500 with backoff then FAILED, while
#                      GET redirects keep returning 302 the whole time
#   3. mysql read_only -> link writes fail, reads and redirect probes survive,
#                      writes recover after read_only off
# Style: scripts/outbox-drill.sh (fail-fast per segment, cleanup via trap).
# Requires: compose stack up, built jar (target/linkforge-*.jar, newest wins), .env.
# Env: APP_PORT (default 8083).
set -u
cd "$(dirname "$0")/.."

APP_PORT="${APP_PORT:-8083}"
BASE_URL="http://localhost:$APP_PORT"
JAR="$(ls -1 target/linkforge-*.jar 2>/dev/null | sort -V | tail -1)"
LOG=/tmp/linkforge-fault-drill.log

fail() { echo "FAULT DRILL FAIL: $1"; exit 1; }
json_str() { grep -o "\"$1\":\"[^\"]*\"" | head -1 | sed "s/\"$1\":\"//;s/\"$//"; }
status_of() { curl -s -o /dev/null -w '%{http_code}' "$@"; }
sql() { docker exec linkforge-mysql mysql -ulinkforge -p"$MYSQL_PASSWORD" linkforge -N -B -e "$1" 2>/dev/null; }
metrics() { curl -s -H "Authorization: Bearer $SCRAPE_TOKEN" "$BASE_URL/actuator/prometheus"; }
stub_pid_on_8099() { netstat -ano 2>/dev/null | grep '127.0.0.1:8099' | grep -i listen | awk '{print $NF}' | head -1; }

[ -f .env ] || fail "no .env (cp .env.example .env)"
# export: this script launches the app itself, so .env must win over any stale
# JWT_SECRET and friends living in the invoking shell's environment
set -a
. ./.env
set +a
: "${MYSQL_PASSWORD:?set MYSQL_PASSWORD in .env}"
[ -f "$JAR" ] || fail "jar missing: mvn -B -DskipTests package first"
docker compose ps --status running 2>/dev/null | grep -q mysql || fail "compose mysql not running"
docker compose ps --status running 2>/dev/null | grep -q redis || fail "compose redis not running (segment 1 stops it itself)"
docker compose ps --status running 2>/dev/null | grep -q rabbitmq || fail "compose rabbitmq not running (webhook deliveries ride the MQ consumer)"

# endpoint saves hard-require an AES-256 master key (503 without one); a fresh
# .env ships it empty, so the drill mints a throwaway key for its own app
if [ -z "${LINKFORGE_WEBHOOK_SECRET_KEY:-}" ]; then
  LINKFORGE_WEBHOOK_SECRET_KEY="$(openssl rand -base64 32 2>/dev/null)" \
    || fail "no LINKFORGE_WEBHOOK_SECRET_KEY in .env and openssl unavailable to generate one"
fi
export LINKFORGE_WEBHOOK_SECRET_KEY
# /actuator/prometheus is token-gated (empty = fail-closed 401), so the drill
# mints its own scrape token for the app instance it boots
SCRAPE_TOKEN="fault-drill-$(date +%s)"

APP_PID=""
STUB_PID=""
REDIS_STOPPED=0
MYSQL_RO=0
cleanup() {
  [ -n "$APP_PID" ] && kill "$APP_PID" 2>/dev/null
  # bash kill may not reach the Windows process; also clear whoever still
  # LISTENs on the stub port (port-scoped, so unrelated processes are safe)
  [ -n "$STUB_PID" ] && kill "$STUB_PID" 2>/dev/null
  STUB_WIN_PID="$(stub_pid_on_8099)"
  [ -n "$STUB_WIN_PID" ] && taskkill //F //PID "$STUB_WIN_PID" >/dev/null 2>&1
  if [ "$REDIS_STOPPED" = "1" ]; then
    docker compose start redis >/dev/null 2>&1
    echo "(redis restarted by cleanup)"
  fi
  if [ "$MYSQL_RO" = "1" ]; then
    docker exec linkforge-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "SET GLOBAL read_only=0" >/dev/null 2>&1
    echo "(mysql read_only cleared by cleanup)"
  fi
}
trap cleanup EXIT

# 0. dedicated app instance: fast webhook retry/backoff, private targets allowed
# for the 127.0.0.1 stub (same IT-only escape hatch, D-Bw2-15)
LINKFORGE_WEBHOOK_MAX_ATTEMPTS=3 \
LINKFORGE_WEBHOOK_BACKOFF_BASE_SECONDS=1 \
LINKFORGE_WEBHOOK_BACKOFF_MAX_SECONDS=2 \
LINKFORGE_WEBHOOK_RELAY_FIXED_DELAY_MS=500 \
LINKFORGE_WEBHOOK_RELAY_INITIAL_DELAY_MS=500 \
LINKFORGE_WEBHOOK_ALLOW_PRIVATE_TARGETS=true \
LINKFORGE_METRICS_SCRAPE_TOKEN="$SCRAPE_TOKEN" \
SERVER_PORT=$APP_PORT \
  java -jar "$JAR" > "$LOG" 2>&1 &
APP_PID=$!
UP=""
for i in $(seq 1 45); do UP=$(curl -s -m 2 "$BASE_URL/api/health" 2>/dev/null); [ -n "$UP" ] && break; sleep 2; done
[ -n "$UP" ] || { tail -5 "$LOG"; fail "app instance did not come up on $APP_PORT"; }
echo "app instance up on $APP_PORT (webhook 3 attempts/backoff 1-2s, relay 500ms, private targets on)"

STAMP=$(date +%s)
REG=$(curl -s -X POST "$BASE_URL/api/auth/register" -H 'Content-Type: application/json' \
  -d "{\"tenantName\":\"fltr-$STAMP\",\"username\":\"fltrer\",\"password\":\"fltr-pass-123\"}")
TOKEN=$(echo "$REG" | json_str token)
[ -n "$TOKEN" ] || fail "register failed: $REG"
mklink() { curl -s -X POST "$BASE_URL/api/links" -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' "$@"; }

# ---- 1. redis segment: L2 + idempotency fail-open, then self-heal ----------
ACODE=$(mklink -d '{"originalUrl":"https://example.com/fltr-a"}' | json_str code)
[ -n "$ACODE" ] || fail "create link A (redis up) failed"
S=$(status_of "$BASE_URL/r/$ACODE")
[ "$S" = "302" ] || fail "precondition redirect (redis up) returned $S (expected 302)"

docker compose stop redis >/dev/null 2>&1 || fail "could not stop redis"
REDIS_STOPPED=1
DOWN=""
for i in $(seq 1 15); do
  docker exec linkforge-redis redis-cli ping >/dev/null 2>&1 || { DOWN=1; break; }
  sleep 1
done
[ "$DOWN" = "1" ] || fail "redis still answers ping after compose stop"
echo "redis stopped"

BJSON=$(mklink -H "Idempotency-Key: fltr-$STAMP" -d '{"originalUrl":"https://example.com/fltr-b"}')
BCODE=$(echo "$BJSON" | json_str code)
[ -n "$BCODE" ] || fail "create with redis down returned no code (idempotency did not fail open?): $BJSON"
S=$(status_of "$BASE_URL/r/$BCODE")
[ "$S" = "302" ] || fail "redirect with redis down returned $S (L2 fail-open must serve the row from DB)"
MET=$(metrics)
echo "$MET" | grep -q 'linkforge_cache_failopen_total.*op="l2_get"' \
  || fail "no linkforge.cache.failopen{op=l2_get} meter after redis-down redirect"
echo "$MET" | grep -Eq 'linkforge_cache_failopen_total.*op="idem_(acquire|get|settle)"' \
  || fail "no idempotency linkforge.cache.failopen meter after redis-down create"
echo "PASS redis: redirect 302 with redis down (L2 fail-open)"
echo "PASS redis: create survives redis down (idempotency fail-open)"

docker compose start redis >/dev/null 2>&1 || fail "could not start redis"
REDIS_STOPPED=0
PONG=""
for i in $(seq 1 30); do PONG=$(docker exec linkforge-redis redis-cli ping 2>/dev/null); [ "$PONG" = "PONG" ] && break; sleep 1; done
[ "$PONG" = "PONG" ] || fail "redis did not come back (no PONG)"
CCODE=$(mklink -d '{"originalUrl":"https://example.com/fltr-c"}' | json_str code)
[ -n "$CCODE" ] || fail "create after redis recovery failed (self-heal broken)"
CACHED=""
for i in $(seq 1 10); do
  CACHED=$(docker exec linkforge-redis redis-cli GET "linkforge:code:$CCODE" 2>/dev/null)
  [ "$CACHED" = "https://example.com/fltr-c" ] && break
  sleep 1
done
[ "$CACHED" = "https://example.com/fltr-c" ] || fail "L2 write did not heal: redis GET linkforge:code:$CCODE = '${CACHED:-<none>}'"
S=$(status_of "$BASE_URL/r/$CCODE")
[ "$S" = "302" ] || fail "redirect after redis recovery returned $S (expected 302)"
echo "PASS redis: self-heals after redis returns"

# ---- 2. webhook segment: 3x500 with backoff -> FAILED, redirects never blocked
# HttpListener binds 127.0.0.1:8099 without admin; loop answers 500 to everything
if ! command -v powershell.exe >/dev/null 2>&1; then
  echo "SKIP webhook: no powershell for the 5xx stub"
else
  PS_STUB='
$L = [System.Net.HttpListener]::new()
$L.Prefixes.Add("http://127.0.0.1:8099/")
$L.Start()
while ($L.IsListening) {
  try {
    $C = $L.GetContext()
    $B = [System.Text.Encoding]::UTF8.GetBytes("drill-5xx")
    $C.Response.StatusCode = 500
    $C.Response.ContentLength64 = $B.Length
    $C.Response.OutputStream.Write($B, 0, $B.Length)
    $C.Response.OutputStream.Close()
  } catch {}
}
'
  powershell.exe -NoProfile -Command "$PS_STUB" > /tmp/linkforge-fault-drill-stub.log 2>&1 &
  STUB_PID=$!
  STUB=""
  for i in $(seq 1 15); do
    [ "$(status_of -m 2 http://127.0.0.1:8099/ping)" = "500" ] && { STUB=1; break; }
    sleep 1
  done
  [ "$STUB" = "1" ] || fail "5xx stub did not come up on 127.0.0.1:8099 (see /tmp/linkforge-fault-drill-stub.log)"
  echo "5xx stub up on 127.0.0.1:8099"

  REG2=$(curl -s -X POST "$BASE_URL/api/auth/register" -H 'Content-Type: application/json' \
    -d "{\"tenantName\":\"fltrw-$STAMP\",\"username\":\"fltrwer\",\"password\":\"fltr-pass-123\"}")
  TOKEN2=$(echo "$REG2" | json_str token)
  [ -n "$TOKEN2" ] || fail "webhook tenant register failed: $REG2"
  EP=$(curl -s -X PUT "$BASE_URL/api/webhooks/endpoint" -H "Authorization: Bearer $TOKEN2" \
    -H 'Content-Type: application/json' \
    -d '{"url":"http://127.0.0.1:8099/hook","enabled":true,"deliveryMode":"PER_CLICK"}')
  echo "$EP" | grep -q '"url":"http://127.0.0.1:8099/hook"' || fail "webhook endpoint save failed: $EP"
  DCODE=$(curl -s -X POST "$BASE_URL/api/links" -H "Authorization: Bearer $TOKEN2" \
    -H 'Content-Type: application/json' -d '{"originalUrl":"https://example.com/fltr-d"}' | json_str code)
  [ -n "$DCODE" ] || fail "create link D failed"
  S=$(status_of "$BASE_URL/r/$DCODE")
  [ "$S" = "302" ] || fail "redirect with a 5xx webhook endpoint returned $S (expected 302)"

  # payload carries data.code (WebhookService.enqueuePerClick), which scopes the row
  DELIVERY_SQL="SELECT CONCAT(status,'|',attempts,'|',COALESCE(last_status_code,0)) FROM lf_webhook_delivery WHERE JSON_UNQUOTE(JSON_EXTRACT(payload,'\$.data.code'))='$DCODE' LIMIT 1"
  ROW=""
  NONBLOCK=0
  for i in $(seq 1 60); do
    ROW=$(sql "$DELIVERY_SQL")
    case "$ROW" in
      FAILED\|*) break ;;
      PENDING\|*)
        if [ "$NONBLOCK" = "0" ]; then
          S=$(status_of "$BASE_URL/r/$DCODE")
          [ "$S" = "302" ] || fail "redirect during pending webhook retries returned $S (expected 302)"
          NONBLOCK=1
        fi
        ;;
    esac
    sleep 2
  done
  case "$ROW" in
    FAILED\|3\|500) ;;
    "") fail "no lf_webhook_delivery row for code $DCODE after 120s (click -> outbox -> MQ -> enqueue chain broken?)" ;;
    *) fail "delivery ended '$ROW' (expected FAILED|3|500)" ;;
  esac
  [ "$NONBLOCK" = "1" ] || fail "delivery reached FAILED before a mid-drain redirect sample was taken (relay too fast to observe non-blocking? lower relay delays)"
  echo "PASS webhook: redirect stays 302 while delivery retries"
  echo "PASS webhook: delivery ends FAILED after max attempts (3x500)"
fi

# ---- 3. mysql-ro segment: writes fail, reads survive, writes recover --------
if [ -z "${MYSQL_ROOT_PASSWORD:-}" ]; then
  echo "SKIP mysql-ro: no MYSQL_ROOT_PASSWORD in .env"
else
  E0CODE=$(mklink -d '{"originalUrl":"https://example.com/fltr-e0"}' | json_str code)
  [ -n "$E0CODE" ] || fail "create link E0 (before read_only) failed"
  S=$(status_of "$BASE_URL/r/$E0CODE")
  [ "$S" = "302" ] || fail "pre-lock redirect for E0 returned $S (expected 302)"

  docker exec linkforge-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "SET GLOBAL read_only=1" >/dev/null 2>&1 \
    || fail "could not set read_only=1 (MYSQL_ROOT_PASSWORD wrong?)"
  MYSQL_RO=1
  EST=$(status_of -X POST "$BASE_URL/api/links" -H "Authorization: Bearer $TOKEN" \
    -H 'Content-Type: application/json' -d '{"originalUrl":"https://example.com/fltr-e"}')
  case "$EST" in
    2*) fail "create succeeded with status $EST while read_only=1 (write unexpectedly survived)" ;;
  esac
  # GET /r/{code} records the click through an inline DB write (ClickRecordService),
  # so the GET itself 500s under read_only; HEAD never records, which makes it the
  # read-only-safe redirect probe
  S=$(status_of -I "$BASE_URL/r/$E0CODE")
  [ "$S" = "302" ] || fail "HEAD redirect under read_only returned $S (expected 302)"
  DET=$(status_of "$BASE_URL/api/links/$E0CODE" -H "Authorization: Bearer $TOKEN")
  [ "$DET" = "200" ] || fail "link detail read under read_only returned $DET (expected 200)"
  STA=$(status_of "$BASE_URL/api/links/$E0CODE/stats" -H "Authorization: Bearer $TOKEN")
  [ "$STA" = "200" ] || fail "stats read under read_only returned $STA (expected 200)"
  G=$(status_of "$BASE_URL/r/$E0CODE")
  echo "note: GET /r/$E0CODE under read_only returned $G (GET records the click via a DB write; HEAD is the read-safe probe)"
  echo "PASS mysql-ro: writes fail while reads/redirect survive"

  docker exec linkforge-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "SET GLOBAL read_only=0" >/dev/null 2>&1 \
    || fail "could not clear read_only"
  MYSQL_RO=0
  E1CODE=$(mklink -d '{"originalUrl":"https://example.com/fltr-e1"}' | json_str code)
  [ -n "$E1CODE" ] || fail "create after read_only off failed (writes did not recover)"
  S=$(status_of "$BASE_URL/r/$E1CODE")
  [ "$S" = "302" ] || fail "redirect for E1 returned $S (expected 302)"
  echo "PASS mysql-ro: writes recover after read_only off"
fi

echo "FAULT DRILL OK"
