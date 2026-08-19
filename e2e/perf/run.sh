#!/usr/bin/env bash
# 把 README「怎麼跑」的手動步驟收斂成一支 script，用 subcommand 分工（像 docker
# compose／git 那樣），不用記一堆各自獨立的檔案路徑。
#
# 用法：
#   ./e2e/perf/run.sh [up]                        起一套可用的系統：基礎設施→app→connector
#                                                  （每步都會偵測已在跑就跳過）
#   ./e2e/perf/run.sh perf                         up ＋ 種庫存 ＋ 跑 k6
#   SKU=... STOCK=... VUS=... ./e2e/perf/run.sh perf
#   KAFKA_CONCURRENCY=4 ./e2e/perf/run.sh up           啟動後核對實際 container concurrency
#   COMPOSE_PROJECT_NAME=order-promising-e2e-clean ./e2e/perf/run.sh up
#                                                        使用隔離的 disposable stack
#   PARTITION_KEY_STRATEGY=stock SKU=HOT-SKU STOCK=500 VUS=1000 ./e2e/perf/run.sh perf
#                                                 v3：SKU 分區 single-writer
#   ./e2e/perf/run.sh down                        拆除基礎設施＋停掉背景 app
#   ./e2e/perf/run.sh seed <SKU> <QUANTITY>        單獨種／重置一筆 StockQuant 庫存
#   ./e2e/perf/run.sh verify <SKU>                 Prometheus／log／DB 三方對照
#   ./e2e/perf/run.sh check-dlt <TOPIC>             撈 DLT topic 內容核對 orderId
#
# `up` 與 `perf` 分開，是因為它們的代價差一個數量級：`up` 起一套能操作的系統，`perf` 會再
# 跑一輪上千 VUS 的壓測。合在一起時，想開操作台看一眼的人只能被迫跑完整輪壓測——或者
# 自己拼 docker compose，然後漏掉 connector 註冊那步，訂單就會永遠停在 PENDING。
#
# `perf` 的 exit code 就是 k6 的 exit code（見 k6/hot-sku-burst.js 的 thresholds）：
# 0 代表這次跑的結果全部符合預期，不用自己讀摘要判斷。`up` 成功就是 0。
set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${ROOT_DIR}/../.." && pwd)"
COMPOSE_FILE="${ROOT_DIR}/docker-compose.yml"
APP_LOG="${APP_LOG:-/tmp/order-promising-e2e-perf.log}"
CONNECT_URL="${CONNECT_URL:-http://localhost:28293}"
CONNECTOR_NAME="${CONNECTOR_NAME:-order-promising-outbox}"
COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-order-promising-e2e-perf}"
export COMPOSE_PROJECT_NAME
POSTGRES_CONTAINER="${POSTGRES_CONTAINER:-${COMPOSE_PROJECT_NAME}-postgres-1}"
NETWORK="${NETWORK:-${COMPOSE_PROJECT_NAME}_default}"

# 壓測自己的主檔。k6 script 以 PERF_OWNER_CODE／PERF_FACILITY_CODE 反查識別碼（見
# k6/hot-sku-burst.js 的 setup），因此 UUID 只寫在這裡一處，不必兩邊同步。
PERF_OWNER_ID="00000000-0000-0000-0000-0000000000f1"
PERF_OWNER_CODE="PERF-OWNER"
PERF_PRODUCT_ID="00000000-0000-0000-0000-0000000000f2"
PERF_PRODUCT_CODE="P-PERF"
PERF_FACILITY_ID="00000000-0000-0000-0000-0000000000f4"
PERF_FACILITY_CODE="WH-PERF"
PERF_LOCATION_ID="00000000-0000-0000-0000-0000000000f6"
PERF_OUTBOUND_TYPE_ID="00000000-0000-0000-0000-0000000000f7"

# 熱點庫存那一列。**id 與兩個日期都固定**，這是熱點壓測的正確性前提：
#
#   庫存以 (貨主, 倉, SKU, 入庫日, 效期) 唯一，所以日期若取「今天」，隔天重跑就會多出
#   第二列。而這支壓測的價值全在「1,000 張單真的搶同一列」所產生的樂觀鎖競爭——庫存一
#   散開，競爭強度就完全不同，而 checks 與 thresholds 仍然會全過。那是最糟的失敗方式。
#
# 效期刻意放到 2099：壓測 fixture 不該有到期日，過期的批配不到貨，而那個失敗會表現成
# 「訂單全部掛帳」，看起來像配貨壞了而不是像 fixture 過期。
PERF_STOCK_QUANT_ID="00000000-0000-0000-0000-0000000000f5"
PERF_IN_DATE="2026-01-01"
PERF_EXPIRY_DATE="2099-12-31"

# 起一套可用的系統。三步的順序不能換：connector 要讀 event_outbox，而那張表是 app 啟動時
# 由 Flyway 建的——app 不在 compose 裡，所以 compose 自己帶不出一套完整的系統。
cmd_up() {
  set -e
  local partition_key_strategy="${PARTITION_KEY_STRATEGY:-order-id}"
  local kafka_concurrency="${KAFKA_CONCURRENCY:-4}"
  local app_started=false

  echo "== 1/3 基礎設施 =="
  docker compose -f "${COMPOSE_FILE}" up -d

  echo
  echo "== 2/3 app（偵測到已在跑會跳過啟動） =="
  if curl -sf -o /dev/null http://localhost:28290/actuator/health; then
    echo "app 已經在跑，略過啟動"
  else
    echo "啟動 app（partition-key-strategy=${partition_key_strategy}，Kafka concurrency=${kafka_concurrency}），log 寫到 ${APP_LOG}"
    (cd "${REPO_ROOT}" && nohup ./gradlew :bootstrap:bootRun \
      --args="--spring.profiles.active=dev --spring.kafka.listener.concurrency=${kafka_concurrency} --management.endpoints.web.exposure.include=prometheus,health --archone.allocation.partition-key-strategy=${partition_key_strategy}" \
      > "${APP_LOG}" 2>&1 &)
    app_started=true
    echo -n "等待 app 就緒"
    for _ in $(seq 1 60); do
      if curl -sf -o /dev/null http://localhost:28290/actuator/health; then
        echo
        break
      fi
      echo -n "."
      sleep 3
    done
    if ! curl -sf -o /dev/null http://localhost:28290/actuator/health; then
      echo "app 啟動逾時，見 ${APP_LOG}" >&2
      exit 1
    fi
  fi

  if [ "${app_started}" = true ]; then
    local allocation_subscription_log=""
    for _ in $(seq 1 20); do
      allocation_subscription_log=$(grep -F "subscriberId=allocation-ordering-events" "${APP_LOG}" \
        | tail -n 1 || true)
      if [[ "${allocation_subscription_log}" == *"concurrency=${kafka_concurrency}"* ]]; then
        break
      fi
      sleep 0.5
    done
    if [[ "${allocation_subscription_log}" != *"concurrency=${kafka_concurrency}"* ]]; then
      echo "Kafka runtime concurrency 驗證失敗：預期 ${kafka_concurrency}，見 ${APP_LOG}" >&2
      exit 1
    fi
    echo "Kafka runtime concurrency 已驗證：allocation-ordering-events=${kafka_concurrency}"
  else
    echo "app 非本次啟動，略過 runtime concurrency 核對；需要重驗時請先執行 ${0} down"
  fi

  echo
  echo "== 3/3 Debezium connector（偵測到已 RUNNING 會跳過註冊） =="
  local status state
  status=$(curl -sf "${CONNECT_URL}/connectors/${CONNECTOR_NAME}/status" 2>/dev/null || echo '{}')
  state=$(echo "${status}" | jq -r '.connector.state // empty')
  if [ "${state}" = "RUNNING" ]; then
    echo "connector 已經是 RUNNING，略過註冊"
  else
    "${ROOT_DIR}/kafka-connect/register-outbox-connector.sh"
  fi

  echo
  echo "系統已就緒：app http://localhost:28290、Kafka UI http://localhost:28294"
  echo "要開操作台：cd frontend && npm install && npm run dev"
  echo "要跑壓測：${0} perf"
}

# 壓測。它先確保系統起來，再種一筆刻意集中的熱點庫存、對它打 k6。
cmd_perf() {
  set -e
  local sku="${SKU:-HOT-SKU}"
  local stock="${STOCK:-500}"
  local vus="${VUS:-1000}"
  local results_file="${RESULTS_FILE:-${ROOT_DIR}/k6/results/hot-sku-burst-$(date +%Y%m%dT%H%M%S).json}"

  cmd_up

  echo
  echo "== 種庫存（SKU=${sku} STOCK=${stock}） =="
  cmd_seed "${sku}" "${stock}"

  echo
  echo "== 跑 k6（VUS=${vus}） =="
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

# Upsert 一筆 StockQuant 庫存。sku 有 UNIQUE 限制，重跑同一個 SKU 會直接把
# on_hand/reserved 重置成指定值，不會累積出重複列或髒資料。
# 把一個 SKU 種成「可下單」：主檔三層 ＋ 倉庫與指派 ＋ 庫存池。
#
# 只種庫存池是不夠的——order_lines 有 FK (owner_id, sku_code) → skus；orders 另有複合 FK
# (owner_id, facility_id) → owner_facilities。少了任何一邊，下單都會被資料庫擋下。
#
# 壓測用自己的貨主而不借用 dev seed 的 OWNER-A：兩者互不依賴，改 demo 的固定資料不會
# 弄壞壓測，反之亦然；資料庫裡也一眼看得出哪些列是壓測產物。
#
# 主檔用 DO NOTHING、庫存池用 DO UPDATE：前者是身分，重跑不該變動；後者正是「重置庫存」
# 這個動作本身的目的。
cmd_seed() {
  local sku="${1:?usage: run.sh seed <SKU> <ON_HAND_QUANTITY>}"
  local quantity="${2:?usage: run.sh seed <SKU> <ON_HAND_QUANTITY>}"

  # 這裡不依賴外層 -e（standalone 呼叫時是關的），失敗要自己判斷、自己中止
  if ! docker exec -i "${POSTGRES_CONTAINER}" psql -U order_promising -d order_promising <<SQL
INSERT INTO owners (id, code, name)
VALUES ('${PERF_OWNER_ID}', '${PERF_OWNER_CODE}', '壓測貨主')
ON CONFLICT (code) DO NOTHING;

INSERT INTO products (id, owner_id, product_code, name, temperature_zone)
VALUES ('${PERF_PRODUCT_ID}', '${PERF_OWNER_ID}', '${PERF_PRODUCT_CODE}', '壓測商品', 'AMBIENT')
ON CONFLICT (owner_id, product_code) DO NOTHING;

INSERT INTO skus (id, owner_id, sku_code, product_code, spec_name, weight_gram)
VALUES (gen_random_uuid(), '${PERF_OWNER_ID}', '${sku}', '${PERF_PRODUCT_CODE}', '${sku}', 1)
ON CONFLICT (owner_id, sku_code) DO NOTHING;

INSERT INTO facilities (id, code, name)
VALUES ('${PERF_FACILITY_ID}', '${PERF_FACILITY_CODE}', '壓測倉')
ON CONFLICT (code) DO NOTHING;

INSERT INTO owner_facilities (owner_id, facility_id)
VALUES ('${PERF_OWNER_ID}', '${PERF_FACILITY_ID}')
ON CONFLICT DO NOTHING;

INSERT INTO stock_locations (id, facility_id, code, name, usage)
VALUES ('${PERF_LOCATION_ID}', '${PERF_FACILITY_ID}', 'WH-PERF/Stock', '壓測設施／庫存', 'INTERNAL')
ON CONFLICT (code) DO NOTHING;

INSERT INTO stock_picking_types (
    id, facility_id, code, name, default_from_location_id, default_to_location_id)
SELECT '${PERF_OUTBOUND_TYPE_ID}', '${PERF_FACILITY_ID}', 'OUTBOUND', '壓測設施出貨',
       '${PERF_LOCATION_ID}', id
  FROM stock_locations
 WHERE usage = 'CUSTOMER'
 ORDER BY id
 LIMIT 1
ON CONFLICT (facility_id, code) DO NOTHING;

-- 以固定 id 做 upsert 而不是 DELETE 後重建：stock_reservations 的外鍵指向這一列，
-- 前一輪壓測留下的預留會讓 DELETE 失敗。UPDATE 沒有這個問題。
INSERT INTO stock_pools (
    id, owner_id, location_id, sku_code, in_date, expiry_date,
    on_hand_quantity, reserved_quantity, version, updated_at)
VALUES ('${PERF_STOCK_QUANT_ID}', '${PERF_OWNER_ID}', '${PERF_LOCATION_ID}', '${sku}',
        DATE '${PERF_IN_DATE}', DATE '${PERF_EXPIRY_DATE}', ${quantity}, 0, 0, now())
ON CONFLICT (id) DO UPDATE
  SET on_hand_quantity = EXCLUDED.on_hand_quantity,
      reserved_quantity = 0,
      version = stock_pools.version + 1,
      updated_at = now();
SQL
  then
    echo "種庫存失敗（SKU=${sku}）" >&2
    return 1
  fi

  # **斷言熱點庫存只有一列。** 上面的 upsert 保證「我們種的那一列」是同一列，但保證不了
  # 「沒有別人種的第二列」——前一輪用不同日期種過、或有人手動補過貨，都會多出一列而讓
  # 競爭分散。散開之後 checks 與 thresholds 仍然全過，所以這裡不查就沒人會發現。
  local batch_count
  batch_count=$(docker exec -i "${POSTGRES_CONTAINER}" psql -U order_promising -d order_promising \
    -tAc "SELECT count(*) FROM stock_pools WHERE owner_id = '${PERF_OWNER_ID}' AND location_id = '${PERF_LOCATION_ID}' AND sku_code = '${sku}'")
  if [ "${batch_count}" != "1" ]; then
    echo "熱點庫存必須只有一列，實際有 ${batch_count} 列——競爭已被分散，這次壓測測不到" >&2
    echo "真實的樂觀鎖衝突。先 ./e2e/perf/run.sh down 重建再跑。" >&2
    return 1
  fi

  echo "已種好 ${sku}：貨主 ${PERF_OWNER_CODE}、倉庫 ${PERF_FACILITY_CODE}、單一批次" \
    "（入庫 ${PERF_IN_DATE}／效期 ${PERF_EXPIRY_DATE}）、on_hand_quantity=${quantity}"
}

# Prometheus 跟 app log 兩種方法互相對照（/actuator/prometheus 沒開的話會是 404，
# 容易誤判成「0 衝突」），外加 DB 直接查詢最終狀態。
cmd_verify() {
  local sku="${1:?usage: run.sh verify <SKU>}"

  echo "== Prometheus 重試計數 =="
  curl -sf http://localhost:28290/actuator/prometheus | grep order_allocation_retry \
    || echo "(打不到或沒有這個 metric；確認 app 啟動時有帶 --management.endpoints.web.exposure.include=prometheus)"

  echo
  echo "== app log 'retry exhausted' 次數（${APP_LOG}） =="
  grep -c "retry exhausted" "${APP_LOG}" 2>/dev/null || echo 0

  echo
  echo "== DB 訂單狀態分布（sku=${sku}） =="
  # SKU 在 order_lines 上而不在 orders 上——訂單是行的集合，訂單層沒有單一 SKU。
  # 這裡用 EXISTS 而不是 join：一張單有多行時 join 會讓它被計數多次，把「幾張單」
  # 悄悄變成「幾行」。目前收單只收一行，兩者剛好相等，正是最容易埋錯的情況。
  docker exec -i "${POSTGRES_CONTAINER}" psql -U order_promising -d order_promising \
    -c "SELECT o.status, count(*) FROM orders o
        WHERE EXISTS (SELECT 1 FROM order_lines ol
                      WHERE ol.order_id = o.id AND ol.sku_code = '${sku}')
        GROUP BY o.status ORDER BY o.status;"
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
  perf) cmd_perf ;;
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
