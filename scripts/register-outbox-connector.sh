#!/bin/sh
set -eu

connect_url="${CONNECT_URL:-http://localhost:28293}"
connector_name="${CONNECTOR_NAME:-archone-outbox}"
slot_name="${SLOT_NAME:-archone_outbox_slot}"
db_host="${DB_HOST:-postgres}"
db_port="${DB_PORT:-5432}"
db_name="${DB_NAME:-order_promising}"
db_username="${DB_USERNAME:-order_promising}"
db_password="${DB_PASSWORD:-order_promising}"

echo "等待 Kafka Connect REST API（${connect_url}）就緒..."
until curl -fsS "${connect_url}/connectors" >/dev/null; do
  sleep 1
done

echo "建立或更新 Debezium Outbox connector '${connector_name}'..."
curl -fsS -X PUT "${connect_url}/connectors/${connector_name}/config" \
  -H "Content-Type: application/json" \
  -d @- <<JSON
{
  "connector.class": "io.debezium.connector.postgresql.PostgresConnector",
  "database.hostname": "${db_host}",
  "database.port": "${db_port}",
  "database.user": "${db_username}",
  "database.password": "${db_password}",
  "database.dbname": "${db_name}",
  "plugin.name": "pgoutput",
  "topic.prefix": "archone",
  "table.include.list": "public.event_outbox",
  "slot.name": "${slot_name}",
  "publication.autocreate.mode": "filtered",
  "transforms": "outbox",
  "transforms.outbox.type": "io.debezium.transforms.outbox.EventRouter",
  "transforms.outbox.route.by.field": "route",
  "transforms.outbox.route.topic.replacement": "\${routedByValue}",
  "transforms.outbox.table.field.event.key": "partition_key",
  "transforms.outbox.table.expand.json.payload": "true",
  "transforms.outbox.table.fields.additional.placement": "type:header:eventType,headers:header:messageHeaders",
  "key.converter": "org.apache.kafka.connect.storage.StringConverter",
  "value.converter": "org.apache.kafka.connect.json.JsonConverter",
  "value.converter.schemas.enable": "false"
}
JSON

attempt=1
while [ "${attempt}" -le 60 ]; do
  status="$(curl -fsS "${connect_url}/connectors/${connector_name}/status" 2>/dev/null || true)"
  running_states="$(printf '%s' "${status}" | tr ',' '\n' | grep -c '"state":"RUNNING"' || true)"
  if [ "${running_states}" -ge 2 ]; then
    echo "Connector '${connector_name}' 已就緒。"
    exit 0
  fi
  attempt=$((attempt + 1))
  sleep 1
done

echo "Connector '${connector_name}' 未在時限內就緒。" >&2
curl -fsS "${connect_url}/connectors/${connector_name}/status" || true
exit 1
