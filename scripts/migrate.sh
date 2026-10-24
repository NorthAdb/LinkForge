#!/usr/bin/env bash
# Flyway migration entry for an existing database (deepen phase A6).
# Runs the same db/migration set the app applies at startup; legacy volumes
# (tables present, no flyway_schema_history) get a baseline at version 1.
# Pre-P2 volumes missing the stats tables should apply db/schema.sql once
# first (README has the note); this script then baselines them.
set -eu
cd "$(dirname "$0")/.."
[ -f .env ] || { echo "migrate FAIL: no .env (cp .env.example .env)"; exit 1; }
. ./.env
: "${SPRING_DATASOURCE_PASSWORD:?set SPRING_DATASOURCE_PASSWORD in .env}"
MVN="${MVN:-mvn}"
if ! command -v "$MVN" >/dev/null 2>&1 && [ ! -x "$MVN" ]; then
  echo "migrate FAIL: mvn not found (install Maven or set MVN=/path/to/mvn)"; exit 1
fi

URL="${SPRING_DATASOURCE_URL:-jdbc:mysql://localhost:3306/linkforge?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false}"
USER="${SPRING_DATASOURCE_USERNAME:-linkforge}"

if ! "$MVN" -B org.flywaydb:flyway-maven-plugin:migrate \
    -Dflyway.url="$URL" -Dflyway.user="$USER" -Dflyway.password="$SPRING_DATASOURCE_PASSWORD"; then
  echo "migrate FAIL: flyway:migrate exited non-zero (see log above)"; exit 1
fi
echo "migrate OK: db/migration applied, flyway_schema_history is the source of truth"
