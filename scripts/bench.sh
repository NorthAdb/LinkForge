#!/usr/bin/env bash
# LinkForge benchmark (P4).
# Tool: wrk (docker image williamyeh/wrk), client in Docker -> app on host.
# Scenarios on the redirect path isolate the synchronous write cost:
#   default  = sync click_log ON + MQ publish ON
#   no-mq    = LINKFORGE_ASYNC_CLICK_ENABLED=false
#   no-sync  = LINKFORGE_SYNC_CLICK_ENABLED=false
# The app is started BY THIS SCRIPT with raised rate limits and stopped on exit.

set -u
cd "$(dirname "$0")/.."

BASE_URL="${BASE_URL:-http://localhost:8080}"
WRK_IMAGE="${WRK_IMAGE:-skandyla/wrk}"
T_CREATE="${T_CREATE:-2}"; C_CREATE="${C_CREATE:-20}"; D_CREATE="${D_CREATE:-20s}"
APP_LOG="$(mktemp)"
# version-agnostic: newest built jar wins, so version bumps never break this script
JAR="$(ls -1 target/linkforge-*.jar 2>/dev/null | sort -V | tail -1)"

fail() { echo "BENCH FAIL: $1"; exit 1; }
stop_app() { [ -n "${APP_PID:-}" ] && kill "$APP_PID" >/dev/null 2>&1; APP_PID=""; sleep 1; }
trap stop_app EXIT

start_app() { # $1 = extra env assignments ("K=V K=V")
  set -a; . ./.env; set +a
  env $1 LINKFORGE_REDIRECT_RATE_MAX=1000000 LINKFORGE_CREATE_RATE_MAX=1000000 \
    java -jar "$JAR" >"$APP_LOG" 2>&1 &
  APP_PID=$!
  for i in $(seq 1 45); do curl -sf "$BASE_URL/api/health" >/dev/null 2>&1 && return 0; sleep 2; done
  tail -5 "$APP_LOG"; fail "app did not start ($1)"
}

run_wrk() { # $1 url  $2 "-tN -cN -dS"
  MSYS_NO_PATHCONV=1 docker run --rm "$WRK_IMAGE" $2 --latency "$1" 2>&1
}

curl -sf "$BASE_URL/api/health" >/dev/null 2>&1 && fail "port 8080 busy, stop the running app first"
[ -f .env ] || fail "no .env (cp .env.example .env)"
[ -f "$JAR" ] || fail "build the jar first (mvn -DskipTests package)"
docker info >/dev/null 2>&1 || fail "docker not reachable"
docker image inspect "$WRK_IMAGE" >/dev/null 2>&1 || docker pull -q "$WRK_IMAGE" || fail "cannot pull $WRK_IMAGE"

echo "== specs =="
uname -s; powershell -NoProfile -Command "(Get-CimInstance Win32_Processor).Name; '{0:N0} GB RAM' -f ((Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory/1GB)" 2>/dev/null | head -3
docker version --format 'docker client={{.Client.Version}} server={{.Server.Version}}'
echo "topology: wrk(container) -> host.docker.internal:8080 -> app(host JDK 21) -> deps(compose, named volumes)"

echo "== prepare =="
start_app ""
json_str() { grep -o "\"$1\":\"[^\"]*\"" | head -1 | sed "s/\"$1\":\"//;s/\"$//"; }
TOKEN=$(curl -s -X POST "$BASE_URL/api/auth/login" -H 'Content-Type: application/json' \
  -d "{\"tenantName\":\"${LINKFORGE_BOOTSTRAP_TENANT:-demo}\",\"username\":\"${LINKFORGE_BOOTSTRAP_USERNAME:-admin}\",\"password\":\"${LINKFORGE_BOOTSTRAP_PASSWORD}\"}" | json_str token)
[ -n "$TOKEN" ] || fail "login failed"
CODE=$(curl -s -X POST "$BASE_URL/api/links" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"originalUrl":"https://example.com/bench-hot","title":"bench"}' | json_str code)
[ -n "$CODE" ] || fail "hot link create failed"
curl -s -o /dev/null "$BASE_URL/r/$CODE"
echo "hot code=$CODE (cache warmed)"

redis_stat() { docker exec linkforge-redis redis-cli INFO stats | tr -d '\r' | grep "^$1:" | cut -d: -f2; }

echo "== bench 0: GET /api/health baseline (no DB writes), t2 c10 10s =="
run_wrk "http://host.docker.internal:8080/api/health" "-t2 -c10 -d10s" | grep -E 'requests in|Requests/sec|  50%|  99%|Socket|Non-2xx'

for SCENARIO in "default" "no-mq" "no-sync"; do
  case "$SCENARIO" in
    default) EXTRA="" ;;
    no-mq)   EXTRA="LINKFORGE_ASYNC_CLICK_ENABLED=false" ;;
    no-sync) EXTRA="LINKFORGE_SYNC_CLICK_ENABLED=false" ;;
  esac
  stop_app; start_app "$EXTRA"
  H0=$(redis_stat keyspace_hits); M0=$(redis_stat keyspace_misses)
  echo "== redirect scenario [$SCENARIO] (t2 c10 15s) =="
  run_wrk "http://host.docker.internal:8080/r/$CODE" "-t2 -c10 -d15s" | tee "/tmp/wrk-$SCENARIO.log" \
    | grep -E 'requests in|Requests/sec|  50%|  99%|Socket|Non-2xx'
  H1=$(redis_stat keyspace_hits); M1=$(redis_stat keyspace_misses)
  echo "redis delta [$SCENARIO]: hits=$((H1-H0)) misses=$((M1-M0))"
done

stop_app; start_app ""
H0=$(redis_stat keyspace_hits); M0=$(redis_stat keyspace_misses)
echo "== headline: GET /r/$CODE (t4 c50 30s) =="
run_wrk "http://host.docker.internal:8080/r/$CODE" "-t4 -c50 -d30s" | tee /tmp/wrk-redirect.log \
  | grep -E 'requests in|Requests/sec|Latency Distribution|  50%|  99%|Socket|Non-2xx|[0-9]*%'
H1=$(redis_stat keyspace_hits); M1=$(redis_stat keyspace_misses)
echo "redis delta [headline]: hits=$((H1-H0)) misses=$((M1-M0))"
if [ $((H1-H0+M1-M0)) -gt 0 ]; then
  echo "hit-rate: $(awk "BEGIN{printf \"%.2f%%\", 100*($H1-$H0)/($H1-$H0+$M1-$M0)}")"
fi

cat > target/wrk-create.lua <<'LUA'
wrk.method = "POST"
wrk.headers["Content-Type"] = "application/json"
wrk.headers["Authorization"] = "Bearer " .. (os.getenv("TOKEN") or "")
wrk.body = "{\"originalUrl\":\"https://example.com/bench-create/static\"}"
LUA
echo "== create: POST /api/links (t$T_CREATE c$C_CREATE $D_CREATE) =="
# Static globals only: a defined request() must return a request string in wrk;
# returning nil aborts with "incomplete request". Script is piped over stdin
# because Windows bind mounts can serve stale entries for freshly written files.
MSYS_NO_PATHCONV=1 docker run -i --rm --entrypoint sh -e TOKEN="$TOKEN" "$WRK_IMAGE" \
  -c "cat > /wrk-create.lua && wrk -t$T_CREATE -c$C_CREATE -d$D_CREATE --latency -s /wrk-create.lua http://host.docker.internal:8080/api/links" \
  < target/wrk-create.lua 2>&1 | tee /tmp/wrk-create.log | grep -E 'requests in|Requests/sec|  50%|  99%|Socket|Non-2xx'

stop_app
echo "BENCH DONE (raw logs under /tmp/wrk-*.log, app log $APP_LOG)"
