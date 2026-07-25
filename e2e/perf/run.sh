#!/usr/bin/env bash
# 把 README「怎麼跑」的手動步驟收斂成一支 script，用 subcommand 分工（像 docker
# compose／git 那樣），不用記一堆各自獨立的檔案路徑。
#
# 用法：
#   ./e2e/perf/run.sh [up]                       起完整流程：基礎設施→app→connector
#                                                  →種庫存→跑 k6（都會偵測已在跑就跳過）
#   SKU=... STOCK=... VUS=... ./e2e/perf/run.sh up
#   ./e2e/perf/run.sh down                        拆除基礎設施＋停掉背景 app
#   PARTITION_KEY_STRATEGY=sku SKU=HOT-SKU STOCK=500 VUS=1000 ./e2e/perf/run.sh up
#                                                 v3：SKU 分區 single-writer
#   ./e2e/perf/run.sh seed <SKU> <QUANTITY>        單獨種／重置一筆 StockPool 庫存
#   ./e2e/perf/run.sh verify <SKU>                 Prometheus／log／DB 三方對照
#   ./e2e/perf/run.sh check-dlt <TOPIC>             撈 DLT topic 內容核對 orderId
#
# `up` 的 exit code 就是 k6 的 exit code（見 k6/hot-sku-burst.js 的 thresholds）：
# 0 代表這次跑的結果全部符合預期，不用自己讀摘要判斷。
set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${ROOT_DIR}/../.." && pwd)"
COMPOSE_FILE="${ROOT_DIR}/docker-compose.yml"
APP_LOG="${APP_LOG:-/tmp/order-promising-e2e-perf.log}"
CONNECT_URL="${CONNECT_URL:-http://localhost:8083}"
CONNECTOR_NAME="${CONNECTOR_NAME:-order-promising-outbox}"
POSTGRES_CONTAINER="${POSTGRES_CONTAINER:-order-promising-e2e-perf-postgres-1}"
NETWORK="${NETWORK:-order-promising-e2e-perf_default}"

cmd_up() {
  set -e
  local sku="${SKU:-HOT-SKU}"
  local stock="${STOCK:-500}"
  local vus="${VUS:-1000}"
  local partition_key_strategy="${PARTITION_KEY_STRATEGY:-order-id}"
  local results_file="${RESULTS_FILE:-${ROOT_DIR}/k6/results/hot-sku-burst-$(date +%Y%m%dT%H%M%S).json}"

  echo "== 1/5 基礎設施 =="
  docker compose -f "${COMPOSE_FILE}" up -d

  echo
  echo "== 2/5 app（偵測到已在跑會跳過啟動） =="
  if curl -sf -o /dev/null http://localhost:8080/actuator/health; then
    echo "app 已經在跑，略過啟動"
  else
    echo "啟動 app（partition-key-strategy=${partition_key_strategy}），log 寫到 ${APP_LOG}"
    (cd "${REPO_ROOT}" && nohup ./gradlew :order-promising:bootRun \
      --args="--spring.profiles.active=dev --spring.kafka.listener.concurrency=4 --management.endpoints.web.exposure.include=prometheus,health --archone.allocation.partition-key-strategy=${partition_key_strategy}" \
      > "${APP_LOG}" 2>&1 &)
    echo -n "等待 app 就緒"
    for _ in $(seq 1 60); do
      if curl -sf -o /dev/null http://localhost:8080/actuator/health; then
        echo
        break
      fi
      echo -n "."
      sleep 3
    done
    if ! curl -sf -o /dev/null http://localhost:8080/actuator/health; then
      echo "app 啟動逾時，見 ${APP_LOG}" >&2
      exit 1
    fi
  fi

  echo
  echo "== 3/5 Debezium connector（偵測到已 RUNNING 會跳過註冊） =="
  local status state
  status=$(curl -sf "${CONNECT_URL}/connectors/${CONNECTOR_NAME}/status" 2>/dev/null || echo '{}')
  state=$(echo "${status}" | jq -r '.connector.state // empty')
  if [ "${state}" = "RUNNING" ]; then
    echo "connector 已經是 RUNNING，略過註冊"
  else
    "${ROOT_DIR}/kafka-connect/register-outbox-connector.sh"
  fi

  echo
  echo "== 4/5 種庫存（SKU=${sku} STOCK=${stock}） =="
  cmd_seed "${sku}" "${stock}"

  echo
  echo "== 5/5 跑 k6（VUS=${vus}） =="
  set +e
  k6 run \
    -e HOT_SKU="${sku}" \
    -e VUS="${vus}" \
    -e EXPECTED_STOCK="${stock}" \
    --summary-trend-stats="avg,min,med,max,p(90),p(95),p(99)" \
    --summary-export="${results_file}" \
    "${ROOT_DIR}/k6/hot-sku-burst.js"
  local k6_exit=$?

  echo
  echo "結果存到 ${results_file}"
  echo "驗證衝突率／DB 狀態：${0} verify ${sku}"
  exit "${k6_exit}"
}

cmd_down() {
  pgrep -f "com.flowzati.archone.ArchoneApplication" | xargs -r kill 2>/dev/null
  docker compose -f "${COMPOSE_FILE}" down -v
}

# Upsert 一筆 StockPool 庫存。sku 有 UNIQUE 限制，重跑同一個 SKU 會直接把
# on_hand/reserved 重置成指定值，不會累積出重複列或髒資料。
cmd_seed() {
  local sku="${1:?usage: run.sh seed <SKU> <ON_HAND_QUANTITY>}"
  local quantity="${2:?usage: run.sh seed <SKU> <ON_HAND_QUANTITY>}"

  # 這裡不依賴外層 -e（standalone 呼叫時是關的），失敗要自己判斷、自己中止
  if ! docker exec -i "${POSTGRES_CONTAINER}" psql -U order_promising -d order_promising <<SQL
INSERT INTO stock_pools (id, sku, on_hand_quantity, reserved_quantity, version, updated_at)
VALUES (gen_random_uuid(), '${sku}', ${quantity}, 0, 0, now())
ON CONFLICT (sku) DO UPDATE
  SET on_hand_quantity = EXCLUDED.on_hand_quantity,
      reserved_quantity = 0,
      version = stock_pools.version + 1,
      updated_at = now();
SQL
  then
    echo "種庫存失敗（SKU=${sku}）" >&2
    return 1
  fi
  echo "已種好 ${sku}：on_hand_quantity=${quantity}"
}

# Prometheus 跟 app log 兩種方法互相對照（/actuator/prometheus 沒開的話會是 404，
# 容易誤判成「0 衝突」），外加 DB 直接查詢最終狀態。
cmd_verify() {
  local sku="${1:?usage: run.sh verify <SKU>}"

  echo "== Prometheus 重試計數 =="
  curl -sf http://localhost:8080/actuator/prometheus | grep order_allocation_retry \
    || echo "(打不到或沒有這個 metric；確認 app 啟動時有帶 --management.endpoints.web.exposure.include=prometheus)"

  echo
  echo "== app log 'retry exhausted' 次數（${APP_LOG}） =="
  grep -c "retry exhausted" "${APP_LOG}" 2>/dev/null || echo 0

  echo
  echo "== DB 訂單狀態分布（sku=${sku}） =="
  docker exec -i "${POSTGRES_CONTAINER}" psql -U order_promising -d order_promising \
    -c "SELECT status, count(*) FROM orders WHERE sku='${sku}' GROUP BY status ORDER BY status;"
}

# apache/kafka-native 是 native image、沒有 bin/*.sh，這裡借一般的 apache/kafka
# image 純粹當 CLI 工具用。用 --timeout-ms 撈完既有訊息後本來就是靠拋
# TimeoutException、非 0 結束代表「讀完了」，不是這個 subcommand 失敗。
cmd_check_dlt() {
  local topic="${1:?usage: run.sh check-dlt <dlt-topic>  例如 ordering.order-events-dlt}"
  local timeout_ms="${TIMEOUT_MS:-8000}"

  docker run --rm --network "${NETWORK}" apache/kafka:4.3.0 \
    /opt/kafka/bin/kafka-console-consumer.sh \
    --bootstrap-server kafka:19092 \
    --topic "${topic}" \
    --from-beginning \
    --timeout-ms "${timeout_ms}" \
    --formatter-property print.key=true
  return 0
}

case "${1:-up}" in
  up) cmd_up ;;
  down) cmd_down ;;
  seed) shift; cmd_seed "$@" ;;
  verify) shift; cmd_verify "$@" ;;
  check-dlt) shift; cmd_check_dlt "$@" ;;
  *)
    echo "未知的 subcommand: ${1}" >&2
    echo "用法見檔案開頭註解，或直接看 e2e/perf/README.md" >&2
    exit 1
    ;;
esac
