#!/usr/bin/env bash
# LinkForge end-to-end smoke:
#   health -> login -> create -> 302 redirect -> redis row
#   register (open signup) -> create -> detail -> update (cache evict) -> disable -> 404
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
status_of() { curl -s -o /dev/null -w '%{http_code}' "$@"; }

# 1. health
curl -sf "$BASE_URL/api/health" >/dev/null || fail "GET /api/health not 200"
echo "PASS health"

# 2. bootstrap login -> token
LOGIN=$(curl -s -X POST "$BASE_URL/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"tenantName\":\"$TENANT\",\"username\":\"$USER\",\"password\":\"$PASS\"}")
TOKEN=$(echo "$LOGIN" | json_str token)
[ -n "$TOKEN" ] || fail "bootstrap login returned no token: $LOGIN"
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

# 4. public redirect 302 + Location (GET, not -I/HEAD: HEAD shares GET semantics but records no click, and the click assertion below needs one)
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

# 6. open registration -> usable token (D3=C: coexists with bootstrap)
STAMP=$(date +%s)
REG=$(curl -s -X POST "$BASE_URL/api/auth/register" \
  -H 'Content-Type: application/json' \
  -d "{\"tenantName\":\"smoke-$STAMP\",\"username\":\"smoker\",\"password\":\"smoke-pass-123\"}")
RTOKEN=$(echo "$REG" | json_str token)
[ -n "$RTOKEN" ] || fail "register returned no token: $REG"
echo "PASS register tenant=smoke-$STAMP"

# 7. lifecycle: create -> detail -> update (302 follows + cache evicted) -> disable -> 404
LIFE="https://example.com/life-$STAMP"
LCODE=$(curl -s -X POST "$BASE_URL/api/links" \
  -H "Authorization: Bearer $RTOKEN" -H 'Content-Type: application/json' \
  -d "{\"originalUrl\":\"$LIFE\"}" | json_str code)
[ -n "$LCODE" ] || fail "lifecycle create returned no code"

DET=$(status_of "$BASE_URL/api/links/$LCODE" -H "Authorization: Bearer $RTOKEN")
[ "$DET" = "200" ] || fail "detail status $DET != 200"

NEW="https://example.com/updated-$STAMP"
UPD=$(curl -s -X PATCH "$BASE_URL/api/links/$LCODE" \
  -H "Authorization: Bearer $RTOKEN" -H 'Content-Type: application/json' \
  -d "{\"originalUrl\":\"$NEW\"}")
[ "$(echo "$UPD" | json_str originalUrl)" = "$NEW" ] || fail "update body wrong: $UPD"
NEWLOC=$(curl -s -D - -o /dev/null "$BASE_URL/r/$LCODE" | tr -d '\r' | grep -i '^location:' | sed 's/^[Ll]ocation: *//')
[ "$NEWLOC" = "$NEW" ] || fail "after update Location '$NEWLOC' != '$NEW'"
echo "PASS lifecycle create/detail/update code=$LCODE"

DIS=$(status_of -X POST "$BASE_URL/api/links/$LCODE/disable" -H "Authorization: Bearer $RTOKEN")
[ "$DIS" = "200" ] || fail "disable status $DIS != 200"
GONE=$(status_of "$BASE_URL/r/$LCODE")
[ "$GONE" = "404" ] || fail "after disable redirect status $GONE != 404"
echo "PASS lifecycle disable -> redirect 404 code=$LCODE"

echo "SMOKE OK"
