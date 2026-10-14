#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
shopt -s nullglob
migrations=("${repository_root}"/database/migrations/V*__*.sql)
if [ "${#migrations[@]}" -eq 0 ]; then
  echo "Missing versioned SQL in database/migrations; refusing to run." >&2
  exit 2
fi
action="${1:-}"
if [ "$#" -gt 0 ]; then shift; fi

case "${action}" in
  info) flyway_args=(info) ;;
  # Pre-migration validation permits new scripts, but rejects changed/missing history.
  validate) flyway_args=('-ignoreMigrationPatterns=*:pending' validate) ;;
  migrate) flyway_args=(migrate) ;;
  # Strict post-migration/deployment gate: no pending or unknown versions.
  check) flyway_args=(validate) ;;
  *)
    echo "Usage: $0 <info|validate|migrate|check> [Docker Compose options]" >&2
    echo "Without Compose options, set FLYWAY_URL, FLYWAY_USER and FLYWAY_PASSWORD." >&2
    exit 2
    ;;
esac

if [ "$#" -eq 0 ]; then
  : "${FLYWAY_URL:?Set FLYWAY_URL to the target PostgreSQL JDBC URL}"
  : "${FLYWAY_USER:?Set FLYWAY_USER}"
  : "${FLYWAY_PASSWORD:?Set FLYWAY_PASSWORD}"
  set -- -f "${repository_root}/database/compose.yml"
fi

# Run only the migration job. Starting infrastructure/applications is the caller's job.
exec docker compose "$@" --profile migration run --rm --no-deps \
  database-migrate "${flyway_args[@]}"
