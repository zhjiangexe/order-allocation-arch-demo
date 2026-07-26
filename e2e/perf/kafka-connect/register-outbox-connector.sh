#!/usr/bin/env bash
# 向 ../docker-compose.yml 啟動的 kafka-connect 服務註冊 Debezium Outbox Event Router
# connector。設定內容跟 order-promising 的 OutboxCdcIntegrationTest（src/sit）逐字一致，
# 確保跟 SIT 套件已經驗證過的行為維持一致。
set -euo pipefail

CONNECT_URL="${CONNECT_URL:-http://localhost:8083}"
CONNECTOR_NAME="${CONNECTOR_NAME:-order-promising-outbox}"
SLOT_NAME="${SLOT_NAME:-order_promising_outbox_slot}"

echo "等待 Kafka Connect REST API（${CONNECT_URL}）就緒..."
until curl -sf "${CONNECT_URL}/connectors" > /dev/null; do
  sleep 1
done

echo "註冊 connector '${CONNECTOR_NAME}' ..."
curl -sf -X POST "${CONNECT_URL}/connectors" \
  -H "Content-Type: application/json" \
  -d @- <<JSON
{
  "name": "${CONNECTOR_NAME}",
  "config": {
    "connector.class": "io.debezium.connector.postgresql.PostgresConnector",
    "database.hostname": "postgres",
    "database.port": 5432,
    "database.user": "order_promising",
    "database.password": "order_promising",
    "database.dbname": "order_promising",
    "plugin.name": "pgoutput",
    "topic.prefix": "order-promising",
    "table.include.list": "public.event_outbox",
    "slot.name": "${SLOT_NAME}",
    "publication.autocreate.mode": "filtered",
    "transforms": "outbox",
    "transforms.outbox.type": "io.debezium.transforms.outbox.EventRouter",
    "transforms.outbox.route.by.field": "route",
    "transforms.outbox.route.topic.replacement": "\${routedByValue}",
    "transforms.outbox.table.field.event.key": "partition_key",
    "transforms.outbox.table.expand.json.payload": true,
    "transforms.outbox.table.fields.additional.placement": "type:header:eventType",
    "key.converter": "org.apache.kafka.connect.storage.StringConverter",
    "value.converter": "org.apache.kafka.connect.json.JsonConverter",
    "value.converter.schemas.enable": false
  }
}
JSON

echo "等待 connector '${CONNECTOR_NAME}' 進入 RUNNING 狀態..."
for _ in $(seq 1 60); do
  STATUS=$(curl -sf "${CONNECT_URL}/connectors/${CONNECTOR_NAME}/status" || echo '{}')
  CONNECTOR_STATE=$(echo "${STATUS}" | jq -r '.connector.state // empty')
  TASK_STATE=$(echo "${STATUS}" | jq -r '.tasks[0].state // empty')
  if [ "${CONNECTOR_STATE}" = "RUNNING" ] && [ "${TASK_STATE}" = "RUNNING" ]; then
    echo "Connector '${CONNECTOR_NAME}' 已經是 RUNNING。"
    exit 0
  fi
  sleep 1
done

echo "等待 connector '${CONNECTOR_NAME}' 進入 RUNNING 逾時，最後狀態："
curl -sf "${CONNECT_URL}/connectors/${CONNECTOR_NAME}/status" || true
exit 1
