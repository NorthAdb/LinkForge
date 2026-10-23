#!/usr/bin/env bash
# LinkForge end-to-end smoke: health -> login -> create -> 302 redirect -> cache row.
# Portable: needs only bash + curl + grep/sed (no python/jq). Run from repo root.
# Env overrides: BASE_URL, LINKFORGE_BOOTSTRAP_TENANT/USERNAME/PASSWORD (or a sourced .env).

set -u
cd "$(dirname "$0")/.."

BASE_URL="${BASE_URL:-http://localhost:8080}"
[ -z "${LINKFORGE_BOOTSTRAP_PASSWORD:-}" ] && [ -f .env ] && set -a && . ./.env && set +a
TENANT="${LINKFORGE_BOOTSTRAP_TENANT:-demo}"
USER="${LINKFORGE_BOOTSTRAP_USERNAME:-admin}"
PASS="${LINKFORGE_BOOTSTRAP_PASSWORD:?set LINKFORGE_BOOTSTRAP_PASSWORD or create .env}"

fail() { echo "SMOKE FAIL: $1"; exit 1; }
json_str() { grep -o "\"$1\":\"[^\"]*\"" | head -1 | sed "s/\"$1\":\"//;s/\"$//"; }

# 1. health
curl -sf "$BASE_URL/api/health" >/dev/null || fail "GET /api/health not 200"
echo "PASS health"

# 2. login -> token
LOGIN=$(curl -s -X POST "$BASE_URL/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"tenantName\":\"$TENANT\",\"username\":\"$USER\",\"password\":\"$PASS\"}")
TOKEN=$(echo "$LOGIN" | json_str token)
[ -n "$TOKEN" ] || fail "login returned no token: $LOGIN"
echo "PASS login user=$USER tenant=$TENANT"

# 3. create short link
TARGET="https://example.com/smoke-$(date +%s)"
CREATE=$(curl -s -X POST "$BASE_URL/api/links" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d "{\"originalUrl\":\"$TARGET\",\"title\":\"smoke\"}")
CODE=$(echo "$CREATE" | json_str code)
[ -n "$CODE" ] || fail "create returned no code: $CREATE"
echo "PASS create code=$CODE"

# 4. public redirect 302 + Location (GET, not -I/HEAD: security only permits GET on /r/**)
HEADERS=$(curl -s -D - -o /dev/null "$BASE_URL/r/$CODE")
echo "$HEADERS" | head -1 | grep -q " 302 " || fail "redirect not 302: $(echo "$HEADERS" | head -1)"
LOCATION=$(echo "$HEADERS" | tr -d '\r' | grep -i '^location:' | sed 's/^[Ll]ocation: *//')
[ "$LOCATION" = "$TARGET" ] || fail "Location '$LOCATION' != '$TARGET'"
echo "PASS redirect code=$CODE location=$LOCATION"

# 5. redis cache row (skipped when redis container is absent)
if docker exec linkforge-redis redis-cli EXISTS "linkforge:code:$CODE" >/dev/null 2>&1; then
  CACHED=$(docker exec linkforge-redis redis-cli GET "linkforge:code:$CODE")
  [ "$CACHED" = "$TARGET" ] || fail "redis cache '$CACHED' != '$TARGET'"
  echo "PASS redis cache key=linkforge:code:$CODE"
else
  echo "SKIP redis check (container linkforge-redis not reachable)"
fi

echo "SMOKE OK"
