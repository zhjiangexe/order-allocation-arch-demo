#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
environment="${1:-}"
action="${2:-}"

usage() {
  echo "Usage: $0 <dev|stage|prod> <package|push|pull|up|deploy|restart|down|logs|ps|config>"
}

case "${environment}" in
  dev|stage|prod) ;;
  *)
    usage
    exit 2
    ;;
esac

case "${action}" in
  package|push|pull|up|deploy|restart|down|logs|ps|config) ;;
  *)
    usage
    exit 2
    ;;
esac

environment_file="${repository_root}/docker/env/${environment}.env"
if [ ! -f "${environment_file}" ]; then
  example_file="${environment_file}.example"
  echo "缺少 ${environment_file}" >&2
  if [ -f "${example_file}" ]; then
    echo "請先複製 ${example_file}，填入該環境的 image、資料庫、Kafka 與密碼設定。" >&2
  fi
  exit 2
fi

export ARCHONE_RUNTIME_ENV_FILE="${environment_file}"

compose=(
  docker compose
  --env-file "${environment_file}"
  -f "${repository_root}/docker/compose.yml"
  -f "${repository_root}/docker/compose.${environment}.yml"
)

start_environment() {
  if [ "${environment}" = "dev" ]; then
    "${compose[@]}" up --detach --build --wait postgres kafka kafka-connect monolith
    "${compose[@]}" --profile tools run --rm connector-init
  else
    "${compose[@]}" up --detach --no-build --wait monolith
  fi
}

case "${action}" in
  package)
    "${compose[@]}" build monolith
    ;;
  push)
    "${compose[@]}" push monolith
    ;;
  pull)
    "${compose[@]}" pull monolith
    ;;
  up)
    start_environment
    ;;
  deploy)
    if [ "${environment}" != "dev" ]; then
      "${compose[@]}" pull monolith
    fi
    start_environment
    ;;
  restart)
    "${compose[@]}" up --detach --no-build --force-recreate --wait monolith
    ;;
  down)
    "${compose[@]}" down --remove-orphans
    ;;
  logs)
    "${compose[@]}" logs --follow --tail 200 monolith
    ;;
  ps)
    "${compose[@]}" ps
    ;;
  config)
    "${compose[@]}" config
    ;;
esac
