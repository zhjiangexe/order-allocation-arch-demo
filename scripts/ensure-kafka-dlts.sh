#!/bin/sh
set -eu

bootstrap_servers="${KAFKA_BOOTSTRAP_SERVERS:-kafka:19092}"
default_partitions="${KAFKA_TOPIC_PARTITIONS:-4}"
kafka_topics=/opt/kafka/bin/kafka-topics.sh

# Keep this list aligned with the destinations used by the application's Kafka consumers.
for source in \
  ordering.order-events \
  inventory.stock-events \
  promising.allocation-events \
  promising.fulfillment-handoffs \
  fulfillment.cancellation-requests \
  wms.shipment-events \
  fulfillment.ordering-cancellation-requests \
  ordering.cancellation-results; do
  "$kafka_topics" --bootstrap-server "$bootstrap_servers" --create --if-not-exists \
    --topic "$source" --partitions "$default_partitions" --replication-factor 1

  source_partitions=$("$kafka_topics" --bootstrap-server "$bootstrap_servers" --describe \
    --topic "$source" | awk 'NR == 1 { for (i = 1; i <= NF; i++) if ($i == "PartitionCount:") { print $(i + 1); exit } }')
  if [ -z "$source_partitions" ]; then
    echo "Cannot determine partition count for $source" >&2
    exit 1
  fi

  dlt="${source}-dlt"
  "$kafka_topics" --bootstrap-server "$bootstrap_servers" --create --if-not-exists \
    --topic "$dlt" --partitions "$source_partitions" --replication-factor 1

  dlt_partitions=$("$kafka_topics" --bootstrap-server "$bootstrap_servers" --describe \
    --topic "$dlt" | awk 'NR == 1 { for (i = 1; i <= NF; i++) if ($i == "PartitionCount:") { print $(i + 1); exit } }')
  if [ -z "$dlt_partitions" ] || [ "$dlt_partitions" -lt "$source_partitions" ]; then
    echo "DLT $dlt needs at least $source_partitions partitions (found ${dlt_partitions:-none})" >&2
    exit 1
  fi
done
