#!/usr/bin/env bash
# 黑箱 E2E：準備隔離環境 → migration / fixture / messaging → 啟動 JVM → Karate → 清理。
set -euo pipefail

# macOS 執行期間防止閒置休眠；caffeinate 隨 runner 結束，不改系統電源設定。
if [ "$(uname -s)" = Darwin ] && [ "${E2E_CAFFEINATE_WRAPPED:-false}" != true ] \
    && command -v caffeinate >/dev/null 2>&1; then
  export E2E_CAFFEINATE_WRAPPED=true
  exec caffeinate -i "$0" "$@"
fi

# 設定：部署決定預設 mode 與 ports；在啟動任何資源前拒絕不支援的組合。
SPEC_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SPEC_DIR}/../.." && pwd)"
COMPOSE_FILE="${REPO_ROOT}/docker/compose.yml"
DEV_COMPOSE_FILE="${REPO_ROOT}/docker/compose.dev.yml"
KARATE_VERSION="2.1.1"
KARATE_SHA256="5eb0e65a997569fa2b36fb27254e15e13514a024d4fbb0fc353813ac91e398e1"
KARATE_JAR="${SPEC_DIR}/.cache/karate-${KARATE_VERSION}.jar"
APP_JAR="${REPO_ROOT}/backend/deployments/monolith/build/libs/archone-monolith.jar"
E2E_DEPLOYMENT="${E2E_DEPLOYMENT:-monolith}"
case "${E2E_DEPLOYMENT}" in
  monolith) default_mode=all; default_app_port=28390; default_infra_base=28390 ;;
  microservice) default_mode=events; default_app_port=28480; default_infra_base=28490 ;;
  *) echo "E2E_DEPLOYMENT 必須為 monolith 或 microservice" >&2; exit 2 ;;
esac
BUILD_DIR="${E2E_BUILD_DIR:-${SPEC_DIR}/build/${E2E_DEPLOYMENT}}"
E2E_MODE="${E2E_MODE:-${default_mode}}"
case "${E2E_MODE}" in
  all|events|temporal) ;;
  *) echo "E2E_MODE 必須為 all、events 或 temporal" >&2; exit 2 ;;
esac
if [ "${E2E_DEPLOYMENT}" = microservice ] && [ "${E2E_MODE}" != events ]; then
  echo "microservice 只支援 E2E_MODE=events" >&2
  exit 2
fi
# 只追蹤本次啟動的 JVM／Compose；清理時不碰既有環境。
APP_PIDS=()
APP_PORTS=()
APP_LOGS=()
STACK_STARTED=false
JAVA_BIN="${E2E_JAVA_BIN:-}"

export COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-archone-karate-e2e-${E2E_DEPLOYMENT}}"
export ARCHONE_POSTGRES_PORT="${ARCHONE_POSTGRES_PORT:-$((default_infra_base + 1))}"
export ARCHONE_KAFKA_PORT="${ARCHONE_KAFKA_PORT:-$((default_infra_base + 2))}"
export ARCHONE_CONNECT_PORT="${ARCHONE_CONNECT_PORT:-$((default_infra_base + 3))}"
export ARCHONE_TEMPORAL_PORT="${ARCHONE_TEMPORAL_PORT:-$((default_infra_base + 4))}"
export ARCHONE_TEMPORAL_UI_PORT="${ARCHONE_TEMPORAL_UI_PORT:-$((default_infra_base + 6))}"
export ARCHONE_KAFKA_UI_PORT="${ARCHONE_KAFKA_UI_PORT:-$((default_infra_base + 7))}"
export E2E_APP_PORT="${E2E_APP_PORT:-${default_app_port}}"
app_service_count=1
if [ "${E2E_DEPLOYMENT}" = microservice ]; then app_service_count=5; fi
if ! [[ "${E2E_APP_PORT}" =~ ^[0-9]+$ ]]; then
  echo "E2E_APP_PORT 必須是可容納所有服務的有效連接埠。" >&2
  exit 2
fi
app_port_number=$((10#${E2E_APP_PORT}))
if (( app_port_number < 1 || app_port_number > 65536 - app_service_count )); then
  echo "E2E_APP_PORT 必須是可容納所有服務的有效連接埠。" >&2
  exit 2
fi

# Monolith 共用一個 HTTP 入口；microservice 依 API owner 分流至各自 port。
export E2E_ORDERING_URL="http://localhost:${E2E_APP_PORT}"
export E2E_INVENTORY_URL="${E2E_ORDERING_URL}"
export E2E_FULFILLMENT_URL="${E2E_ORDERING_URL}"
export E2E_LOGISTICS_DATA_URL="${E2E_ORDERING_URL}"
if [ "${E2E_DEPLOYMENT}" = microservice ]; then
  E2E_INVENTORY_URL="http://localhost:$((E2E_APP_PORT + 1))"
  E2E_FULFILLMENT_URL="http://localhost:$((E2E_APP_PORT + 3))"
  E2E_LOGISTICS_DATA_URL="http://localhost:$((E2E_APP_PORT + 4))"
fi

compose() {
  # 共用基礎設施設定加上本機 ports；project name 由環境變數隔離。
  docker compose -f "${COMPOSE_FILE}" -f "${DEV_COMPOSE_FILE}" "$@"
}

stop_app() {
  # 先通知所有服務停止；30 秒後強制結束，避免清理永久卡住。
  local pid deadline running
  if [ "${#APP_PIDS[@]}" -eq 0 ]; then return 0; fi
  for pid in "${APP_PIDS[@]}"; do
    if kill -0 "${pid}" 2>/dev/null; then kill "${pid}" 2>/dev/null || true; fi
  done
  deadline=$((SECONDS + 30))
  while (( SECONDS < deadline )); do
    running=false
    for pid in "${APP_PIDS[@]}"; do
      if kill -0 "${pid}" 2>/dev/null; then running=true; break; fi
    done
    if [ "${running}" = false ]; then break; fi
    sleep 1
  done
  for pid in "${APP_PIDS[@]}"; do
    if kill -0 "${pid}" 2>/dev/null; then kill -KILL "${pid}" 2>/dev/null || true; fi
    wait "${pid}" 2>/dev/null || true
  done
  APP_PIDS=()
  APP_PORTS=()
  APP_LOGS=()
}

cleanup() {
  # 保留失敗時的 infra log 與原始 exit code；JVM 一律停止。
  local exit_code=$?
  trap '' INT TERM
  stop_app
  if [ "${STACK_STARTED}" = true ] && [ "${exit_code}" -ne 0 ]; then
    mkdir -p "${BUILD_DIR}"
    compose --profile temporal logs --no-color >"${BUILD_DIR}/infra.log" 2>&1 || true
  fi
  if [ "${STACK_STARTED}" = true ] && [ "${KEEP_E2E_STACK:-false}" != "true" ]; then
    compose --profile temporal down -v --remove-orphans >/dev/null 2>&1 || true
  fi
  exit "${exit_code}"
}

trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

resolve_java() {
  # 優先使用明確指定的 Java，否則從 Gradle toolchain 找 Java 25。
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
  # 快取 standalone runner；即使已下載，每次仍驗證固定 checksum。
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
  # 同時啟動所選部署的所有 JVM，分別記錄 PID、port 與 log，再等待全數 health。
  local mode="$1"
  local processing_delay="${2:-0s}"
  local phase_name="${3:-${mode}}"
  # 取消釋放後由既有 reconciliation 喚醒等待訂單；取消階段縮短間隔，避免等待預設 15 分鐘。
  local reconciliation_delay=900000
  case "${phase_name}" in
    *-cancellation-window) reconciliation_delay=1000 ;;
  esac
  local services=(monolith)
  if [ "${E2E_DEPLOYMENT}" = microservice ]; then
    services=(ordering inventory wms fulfillment logistics-data)
  fi
  local service port jar log_file i=0
  echo "啟動 ${E2E_DEPLOYMENT} / ${mode}（WMS delay=${processing_delay}）..."
  for service in "${services[@]}"; do
    port=$((E2E_APP_PORT + i))
    jar="${APP_JAR}"
    if [ "${E2E_DEPLOYMENT}" = microservice ]; then
      jar="${REPO_ROOT}/backend/deployments/microservice/${service}-service/build/libs/archone-${service}-e2e.jar"
    fi
    log_file="${BUILD_DIR}/${service}-${phase_name}.log"
    # 兩種部署都使用共用 fixture，停用 dev seed；migration 已由獨立步驟完成。
    "${JAVA_BIN}" -Xmx384m -jar "${jar}" \
      --spring.profiles.active=e2e \
      --server.port="${port}" \
      --spring.datasource.url="jdbc:postgresql://localhost:${ARCHONE_POSTGRES_PORT}/order_promising" \
      --spring.datasource.username=order_promising --spring.datasource.password=order_promising \
      --spring.kafka.bootstrap-servers="localhost:${ARCHONE_KAFKA_PORT}" \
      --spring.flyway.enabled=false \
      --archone.fulfillment.orchestration-mode="${mode}" \
      --archone.temporal.target="localhost:${ARCHONE_TEMPORAL_PORT}" \
      --archone.wms.simulation.enabled=true \
      --archone.wms.simulation.processing-delay="${processing_delay}" \
      --archone.allocation.reconciliation-scheduler-delay-ms="${reconciliation_delay}" \
      --archone.services.ordering="${E2E_ORDERING_URL}" \
      --archone.services.inventory="${E2E_INVENTORY_URL}" \
      --archone.services.wms="http://localhost:$((E2E_APP_PORT + 2))" \
      >"${log_file}" 2>&1 &
    APP_PIDS+=("$!")
    APP_PORTS+=("${port}")
    APP_LOGS+=("${log_file}")
    i=$((i + 1))
  done
  local ready deadline=$((SECONDS + 120))
  while (( SECONDS < deadline )); do
    ready=true
    for ((i=0; i<${#APP_PIDS[@]}; i++)); do
      if ! kill -0 "${APP_PIDS[$i]}" 2>/dev/null; then
        echo "服務啟動失敗：${APP_LOGS[$i]}" >&2
        tail -n 80 "${APP_LOGS[$i]}" >&2
        return 1
      fi
      if ! curl --noproxy '*' --connect-timeout 1 --max-time 1 -fsS -o /dev/null \
        "http://localhost:${APP_PORTS[$i]}/actuator/health" 2>/dev/null; then
        ready=false
      fi
    done
    if [ "${ready}" = true ]; then return 0; fi
    sleep 1
  done
  echo "服務啟動逾時，logs：${BUILD_DIR}" >&2
  return 1
}

seed_e2e_fixtures() {
  # 只適用全新資料庫；ON CONFLICT 不會還原上次測試已消耗的庫存。
  echo "建立彼此隔離的 E2E SKU 與庫存 fixture..."
  compose exec -T postgres \
    psql -v ON_ERROR_STOP=1 -U order_promising -d order_promising \
    <"${SPEC_DIR}/fixtures/e2e-catalog.sql"
}

run_feature() {
  # 每份 feature 有獨立報告目錄；Java 非零結束碼會中止後續案例並觸發清理。
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

# 1. 準備執行工具，確認不會沿用或清除既有的同名測試環境。
mkdir -p "${BUILD_DIR}"
resolve_java
download_karate

# fixture 需要乾淨資料；檢查容器與 volume，避免誤用 KEEP_E2E_STACK 留下的資料。
docker info >/dev/null
if [ -n "$(docker ps -aq --filter "label=com.docker.compose.project=${COMPOSE_PROJECT_NAME}")" ] \
    || [ -n "$(docker volume ls -q --filter "label=com.docker.compose.project=${COMPOSE_PROJECT_NAME}")" ]; then
  echo "${COMPOSE_PROJECT_NAME} 已有容器或資料 volume；請另選 COMPOSE_PROJECT_NAME 或自行清理該測試環境。" >&2
  exit 2
fi

# 2. 打包。microservice 固定使用 e2e 版本，讓啟動時可直接定位各服務 JAR。
echo "建立 ${E2E_DEPLOYMENT} executable JARs..."
if [ "${E2E_DEPLOYMENT}" = microservice ]; then
  "${REPO_ROOT}/backend/gradlew" -p "${REPO_ROOT}/backend" :deployments:microservice:bootJars -PserviceVersion=e2e --console=plain
else
  "${REPO_ROOT}/backend/gradlew" -p "${REPO_ROOT}/backend" :deployments:monolith:bootJar --console=plain
fi

# 3. 啟動基礎設施。先標記擁有此環境，讓部分啟動失敗也會觸發清理。
echo "啟動隔離的基礎設施（${E2E_MODE}）..."
STACK_STARTED=true
if [ "${E2E_MODE}" = "events" ]; then
  compose up -d --wait postgres kafka kafka-connect
else
  compose --profile temporal up -d --wait postgres kafka kafka-connect temporal
fi

# 4. 先完成 schema 與資料，再建 topics／connector，最後才啟動應用程式。
echo "執行獨立 schema migration..."
"${REPO_ROOT}/scripts/migrate.sh" migrate -f "${COMPOSE_FILE}" -f "${DEV_COMPOSE_FILE}"
"${REPO_ROOT}/scripts/migrate.sh" check -f "${COMPOSE_FILE}" -f "${DEV_COMPOSE_FILE}"

seed_e2e_fixtures
compose run --rm --no-deps kafka-dlt-init
CONNECT_URL="http://localhost:${ARCHONE_CONNECT_PORT}" \
CONNECTOR_NAME="archone-karate-e2e-outbox" \
SLOT_NAME="archone_karate_e2e_outbox_slot" \
  "${REPO_ROOT}/e2e/perf/kafka-connect/register-outbox-connector.sh"
# 5. 共用 API 契約只跑一次；Events 與 Temporal 使用各自的 SKU，避免互相耗用庫存。
initial_mode=events
if [ "${E2E_MODE}" = "temporal" ]; then initial_mode=temporal; fi
start_app "${initial_mode}" 0s "${initial_mode}"
run_feature "catalog-and-idempotency.feature" "catalog-and-idempotency"

if [ "${E2E_MODE}" != "temporal" ]; then
  # 一般流程使用零延遲；Shipment 取消需要重啟為 30 秒延遲以保留操作窗口。
  run_feature "events-fulfillment.feature" "events"
  run_feature "events-cancellation.feature" "events-cancellation"
  run_feature "connector-catch-up.feature" "connector-catch-up"
  stop_app
  start_app events 30s events-cancellation-window
  run_feature "events-shipment-cancellation.feature" "events-shipment-cancellation"
fi

if [ "${E2E_MODE}" != "events" ]; then
  # temporal 單獨執行時，沿用共用契約階段已啟動的 JVM。
  if [ "${E2E_MODE}" != "temporal" ]; then
    stop_app
    start_app temporal 0s temporal
  fi
  run_feature "temporal-fulfillment.feature" "temporal"
  stop_app
  start_app temporal 30s temporal-cancellation-window
  run_feature "temporal-cancellation.feature" "temporal-cancellation"
fi

# EXIT trap 在這之後停止 JVM 並移除環境；只有所有 feature 通過才印出此訊息。
echo "Karate E2E ${E2E_DEPLOYMENT} / ${E2E_MODE} 通過。報告位於 ${BUILD_DIR}/reports"
