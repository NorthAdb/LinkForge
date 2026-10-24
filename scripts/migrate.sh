#!/usr/bin/env bash
# Idempotent schema migration for an existing compose volume (auditor note L2).
# schema.sql is all CREATE TABLE IF NOT EXISTS, so this is safe to re-run.
set -eu
cd "$(dirname "$0")/.."
[ -f .env ] || { echo "migrate FAIL: no .env (cp .env.example .env)"; exit 1; }
. ./.env
: "${MYSQL_ROOT_PASSWORD:?set MYSQL_ROOT_PASSWORD in .env}"
docker exec -i linkforge-mysql mysql -uroot -p"${MYSQL_ROOT_PASSWORD}" \
  < src/main/resources/db/schema.sql
echo "migrate OK: schema.sql applied (idempotent; fresh volumes get it automatically)"
