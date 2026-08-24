#!/usr/bin/env bash
set -euo pipefail

SPEC_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SPEC_DIR}/../.." && pwd)"
COMPOSE_FILE="${REPO_ROOT}/docker/compose.yml"
DEV_COMPOSE_FILE="${REPO_ROOT}/docker/compose.dev.yml"
KARATE_VERSION="2.1.1"
KARATE_SHA256="5eb0e65a997569fa2b36fb27254e15e13514a024d4fbb0fc353813ac91e398e1"
KARATE_JAR="${SPEC_DIR}/.cache/karate-${KARATE_VERSION}.jar"
APP_JAR="${REPO_ROOT}/backend/deployments/monolith/build/libs/archone-monolith.jar"
BUILD_DIR="${SPEC_DIR}/build"
APP_PID=""
JAVA_BIN="${E2E_JAVA_BIN:-}"

export COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-archone-karate-e2e}"
export ARCHONE_POSTGRES_PORT="${ARCHONE_POSTGRES_PORT:-28391}"
export ARCHONE_KAFKA_PORT="${ARCHONE_KAFKA_PORT:-28392}"
export ARCHONE_CONNECT_PORT="${ARCHONE_CONNECT_PORT:-28393}"
export ARCHONE_TEMPORAL_PORT="${ARCHONE_TEMPORAL_PORT:-28394}"
export ARCHONE_TEMPORAL_UI_PORT="${ARCHONE_TEMPORAL_UI_PORT:-28396}"
export ARCHONE_KAFKA_UI_PORT="${ARCHONE_KAFKA_UI_PORT:-28397}"
export E2E_APP_PORT="${E2E_APP_PORT:-28390}"

compose() {
  docker compose -f "${COMPOSE_FILE}" -f "${DEV_COMPOSE_FILE}" "$@"
}

stop_app() {
  if [ -n "${APP_PID}" ] && kill -0 "${APP_PID}" 2>/dev/null; then
    kill "${APP_PID}"
    wait "${APP_PID}" 2>/dev/null || true
  fi
  APP_PID=""
}

cleanup() {
  local exit_code=$?
  stop_app
  if [ "${KEEP_E2E_STACK:-false}" != "true" ]; then
    compose --profile temporal down -v --remove-orphans >/dev/null 2>&1 || true
  fi
  exit "${exit_code}"
}

trap cleanup EXIT INT TERM

resolve_java() {
  if [ -n "${JAVA_BIN}" ]; then
    if [ ! -x "${JAVA_BIN}" ]; then
      echo "E2E_JAVA_BIN 不是可執行檔：${JAVA_BIN}" >&2
      return 1
    fi
    return 0
  fi

  local java_home
  java_home="$("${REPO_ROOT}/backend/gradlew" -p "${REPO_ROOT}/backend" -q javaToolchains \
    | awk '/Location:/ { location=$3 } /Language Version:[[:space:]]+25/ { print location; exit }')"
  JAVA_BIN="${java_home}/bin/java"
  if [ ! -x "${JAVA_BIN}" ]; then
    echo "找不到 Java 25；可用 E2E_JAVA_BIN 指定 java executable。" >&2
    return 1
  fi
}

download_karate() {
  mkdir -p "${SPEC_DIR}/.cache"
  if [ ! -f "${KARATE_JAR}" ]; then
    echo "下載 Karate ${KARATE_VERSION}..."
    curl -fsSL -o "${KARATE_JAR}.tmp" \
      "https://github.com/karatelabs/karate/releases/download/v${KARATE_VERSION}/karate-${KARATE_VERSION}.jar"
    mv "${KARATE_JAR}.tmp" "${KARATE_JAR}"
  fi

  local actual_sha256
  actual_sha256="$(shasum -a 256 "${KARATE_JAR}" | awk '{print $1}')"
  if [ "${actual_sha256}" != "${KARATE_SHA256}" ]; then
    echo "Karate JAR checksum 不符：${actual_sha256}" >&2
    return 1
  fi
}

start_app() {
  local mode="$1"
  local processing_delay="${2:-0s}"
  local phase_name="${3:-${mode}}"
  local log_file="${BUILD_DIR}/app-${phase_name}.log"

  echo "啟動 ${mode} mode app（WMS simulation delay=${processing_delay}）..."
  ORDER_PROMISING_PORT="${E2E_APP_PORT}" \
  ORDER_PROMISING_DB_URL="jdbc:postgresql://localhost:${ARCHONE_POSTGRES_PORT}/order_promising" \
  ORDER_PROMISING_KAFKA_BOOTSTRAP_SERVERS="localhost:${ARCHONE_KAFKA_PORT}" \
  ORDER_PROMISING_FULFILLMENT_ORCHESTRATION_MODE="${mode}" \
  ORDER_PROMISING_TEMPORAL_TARGET="localhost:${ARCHONE_TEMPORAL_PORT}" \
  ORDER_PROMISING_WMS_SIMULATION_PROCESSING_DELAY="${processing_delay}" \
  "${JAVA_BIN}" -jar "${APP_JAR}" --spring.profiles.active=dev >"${log_file}" 2>&1 &
  APP_PID=$!

  for _ in $(seq 1 120); do
    if curl -fsS -o /dev/null "http://localhost:${E2E_APP_PORT}/actuator/health"; then
      return 0
    fi
    if ! kill -0 "${APP_PID}" 2>/dev/null; then
      echo "${mode} mode app 啟動失敗：" >&2
      tail -n 80 "${log_file}" >&2
      return 1
    fi
    sleep 1
  done

  echo "${mode} mode app 啟動逾時，log：${log_file}" >&2
  tail -n 80 "${log_file}" >&2
  return 1
}

seed_e2e_fixtures() {
  echo "建立彼此隔離的 E2E SKU 與庫存 fixture..."
  compose exec -T postgres \
    psql -v ON_ERROR_STOP=1 -U order_promising -d order_promising \
    <"${SPEC_DIR}/fixtures/e2e-catalog.sql"
}

run_feature() {
  local feature="$1"
  local report_name="$2"

  E2E_BASE_URL="http://localhost:${E2E_APP_PORT}" \
  E2E_CONNECT_URL="http://localhost:${ARCHONE_CONNECT_PORT}" \
  E2E_CONNECTOR_NAME="archone-karate-e2e-outbox" \
  "${JAVA_BIN}" -jar "${KARATE_JAR}" run \
    --no-color \
    --clean \
    --backup-reportdir=false \
    --format html,junit:xml \
    --configdir "${SPEC_DIR}" \
    --output "${BUILD_DIR}/reports/${report_name}" \
    "${SPEC_DIR}/features/${feature}"
}

mkdir -p "${BUILD_DIR}"
resolve_java
download_karate

echo "建立 monolith executable JAR..."
"${REPO_ROOT}/backend/gradlew" -p "${REPO_ROOT}/backend" :deployments:monolith:bootJar --console=plain

echo "啟動隔離的 PostgreSQL、Kafka、Debezium Connect 與 Temporal..."
compose --profile temporal up -d --wait postgres kafka kafka-connect temporal

start_app events 0s events
seed_e2e_fixtures
CONNECT_URL="http://localhost:${ARCHONE_CONNECT_PORT}" \
CONNECTOR_NAME="archone-karate-e2e-outbox" \
SLOT_NAME="archone_karate_e2e_outbox_slot" \
  "${REPO_ROOT}/e2e/perf/kafka-connect/register-outbox-connector.sh"
run_feature "catalog-and-idempotency.feature" "catalog-and-idempotency"
run_feature "events-fulfillment.feature" "events"
run_feature "events-cancellation.feature" "events-cancellation"
run_feature "connector-catch-up.feature" "connector-catch-up"

stop_app
start_app events 30s events-cancellation-window
run_feature "events-shipment-cancellation.feature" "events-shipment-cancellation"

stop_app
start_app temporal 0s temporal
run_feature "temporal-fulfillment.feature" "temporal"

stop_app
start_app temporal 30s temporal-cancellation-window
run_feature "temporal-cancellation.feature" "temporal-cancellation"

echo "Karate E2E 全部通過。報告位於 ${BUILD_DIR}/reports"
