#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
action="${1:-up}"
mode="${2:-events}"
case "${action}" in
  up|down|restart|logs|ps|config) ;;
  *) echo "Usage: $0 <up|down|restart|logs|ps|config> <events|temporal>" >&2; exit 2 ;;
esac
case "${mode}" in
  events) port_base=28690 ;;
  temporal) port_base=28790 ;;
  *) echo "MODE 必須是 events 或 temporal" >&2; exit 2 ;;
esac

# 每個模式有固定的 project、volume 與 ports，不切換既有訂單的 driver。
export ARCHONE_RUNTIME_ENV_FILE="${repository_root}/docker/env/dev.env"
export ARCHONE_COMPOSE_PROJECT="archone-demo-${mode}"
export ARCHONE_IMAGE=archone-monolith
export ARCHONE_IMAGE_TAG="demo-${mode}"
export ARCHONE_HTTP_BIND_ADDRESS=127.0.0.1
export ARCHONE_HTTP_PORT="${port_base}"
export ARCHONE_POSTGRES_PORT="$((port_base + 1))"
export ARCHONE_KAFKA_PORT="$((port_base + 2))"
export ARCHONE_CONNECT_PORT="$((port_base + 3))"
export ARCHONE_TEMPORAL_PORT="$((port_base + 4))"
export ARCHONE_FRONTEND_PORT="$((port_base + 5))"
export ARCHONE_TEMPORAL_UI_PORT="$((port_base + 6))"
export ARCHONE_KAFKA_UI_PORT="$((port_base + 7))"
export ARCHONE_MANAGEMENT_PORT="$((port_base + 8))"
export ORDER_PROMISING_FULFILLMENT_ORCHESTRATION_MODE="${mode}"
export ORDER_PROMISING_TEMPORAL_TARGET=temporal:7233

compose=(docker compose --env-file "${ARCHONE_RUNTIME_ENV_FILE}"
  -f "${repository_root}/docker/compose.yml"
  -f "${repository_root}/docker/compose.dev.yml"
  -f "${repository_root}/docker/compose.demo.yml")
if [ "${mode}" = temporal ]; then compose+=(--profile temporal); fi

show_urls() {
  echo "${mode} 簡報環境已就緒"
  echo "操作台：http://localhost:${ARCHONE_FRONTEND_PORT}"
  echo "後端 API：http://localhost:${ARCHONE_HTTP_PORT}"
  echo "Kafka UI：http://localhost:${ARCHONE_KAFKA_UI_PORT}"
  if [ "${mode}" = temporal ]; then echo "Temporal UI：http://localhost:${ARCHONE_TEMPORAL_UI_PORT}"; fi
}

case "${action}" in
  up|restart)
    command -v docker >/dev/null || { echo "請先安裝並啟動 Docker（含 Compose v2）。" >&2; exit 1; }
    docker info >/dev/null
    # restart 同樣重建，確保簡報使用目前工作目錄的程式碼。
    start_args=(up --detach --build --wait --wait-timeout 300)
    if [ "${action}" = restart ]; then start_args+=(--force-recreate); fi
    services=(postgres kafka kafka-connect kafka-ui monolith frontend)
    if [ "${mode}" = temporal ]; then services+=(temporal); fi
    "${compose[@]}" "${start_args[@]}" "${services[@]}"
    "${compose[@]}" --profile tools run --rm connector-init
    show_urls
    ;;
  down) "${compose[@]}" down --remove-orphans ;;
  logs) "${compose[@]}" logs --follow --tail 100 ;;
  ps) "${compose[@]}" ps ;;
  config) "${compose[@]}" config ;;
esac
