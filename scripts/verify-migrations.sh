#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
project="archone-migration-verify-$$"
scratch="$(mktemp -d)"
compose=(-p "${project}" -f "${repository_root}/database/compose.yml"
  -f "${repository_root}/database/compose.test.yml")
cleanup() {
  docker compose "${compose[@]}" --profile migration down --remove-orphans >/dev/null 2>&1 || true
  rm -rf "${scratch}"
}
trap cleanup EXIT

migration() { "${repository_root}/scripts/migrate.sh" "$1" "${compose[@]}"; }
expect_failure() {
  if "$@"; then
    echo "Expected migration gate to reject this state." >&2
    exit 1
  fi
}

docker compose "${compose[@]}" up -d --wait postgres
echo 'Check must reject an empty database; pre-migration validation permits pending SQL.'
expect_failure migration check
migration validate
migration migrate
migration check
migration migrate

applied="$(docker compose "${compose[@]}" exec -T postgres \
  psql -U migration_test -d migration_test -Atc 'SELECT count(*) FROM flyway_schema_history WHERE success')"
expected="$(find "${repository_root}/database/migrations" -name 'V*__*.sql' | wc -l | tr -d ' ')"
test "${applied}" = "${expected}"

# Run negative checks with copies; never edit the canonical SQL or repair history.
cp "${repository_root}"/database/migrations/*.sql "${scratch}/"
cat >"${scratch}/override.yml" <<EOF
services:
  database-migrate:
    volumes:
      - ${scratch}:/flyway/sql:ro
EOF
printf '\nSELECT 1;\n' >>"${scratch}/V1__create_catalog_tables.sql"
expect_failure "${repository_root}/scripts/migrate.sh" validate \
  "${compose[@]}" -f "${scratch}/override.yml"
cp "${repository_root}/database/migrations/V1__create_catalog_tables.sql" "${scratch}/"
rm "${scratch}/V8__record_cancellation_process_outcomes.sql"
expect_failure "${repository_root}/scripts/migrate.sh" check \
  "${compose[@]}" -f "${scratch}/override.yml"

echo 'Verify an existing V1–V7 database upgrades to the current schema.'
rm "${scratch}"/*.sql
cp "${repository_root}"/database/migrations/V[1-7]__*.sql "${scratch}/"
docker compose "${compose[@]}" exec -T postgres \
  psql -v ON_ERROR_STOP=1 -U migration_test -d migration_test -c 'CREATE DATABASE migration_upgrade'
export FLYWAY_UPGRADE_URL=jdbc:postgresql://postgres:5432/migration_upgrade
cat >>"${scratch}/override.yml" <<'EOF'
    environment:
      FLYWAY_URL: ${FLYWAY_UPGRADE_URL}
EOF
"${repository_root}/scripts/migrate.sh" migrate "${compose[@]}" -f "${scratch}/override.yml"
cp "${repository_root}"/database/migrations/*.sql "${scratch}/"
expect_failure "${repository_root}/scripts/migrate.sh" check "${compose[@]}" -f "${scratch}/override.yml"
"${repository_root}/scripts/migrate.sh" migrate "${compose[@]}" -f "${scratch}/override.yml"
"${repository_root}/scripts/migrate.sh" check "${compose[@]}" -f "${scratch}/override.yml"
echo 'Migration verification passed.'
