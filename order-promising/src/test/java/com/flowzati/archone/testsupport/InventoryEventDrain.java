package com.flowzati.archone.testsupport;

import com.flowzati.archone.contracts.inventory.v1.InventoryChannels;
import com.flowzati.archone.inventory.allocation.application.event.AllocationEventSubscriptions;
import com.flowzati.archone.messaging.api.Message;
import com.flowzati.archone.messaging.kafka.KafkaMessageMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.jdbc.core.JdbcTemplate;

/** Delivers pending stock Outbox events in SIT where Debezium and Kafka listeners are disabled. */
public final class InventoryEventDrain {

    private static final int MAX_DELIVERIES = 10_000;

    private final JdbcTemplate jdbcTemplate;
    private final KafkaMessageMapper messageMapper;
    private final BiConsumer<String, Message> emitter;

    public InventoryEventDrain(
            JdbcTemplate jdbcTemplate, KafkaMessageMapper messageMapper, BiConsumer<String, Message> emitter) {
        this.jdbcTemplate = jdbcTemplate;
        this.messageMapper = messageMapper;
        this.emitter = emitter;
    }

    /** Delivers pending availability events. Later backorder rounds belong to the scheduler. */
    public int drain() {
        int delivered = 0;
        while (true) {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    """
          SELECT o.id, o.type, o.partition_key, o.payload, o.headers
            FROM event_outbox o
           WHERE o.route = ?
             AND NOT EXISTS (
                   SELECT 1 FROM event_inbox i
                    WHERE i.subscriber_id = ?
                      AND i.event_id = o.id
                 )
           ORDER BY o.timestamp, o.id
          """, InventoryChannels.STOCK_EVENTS, AllocationEventSubscriptions.INVENTORY_AVAILABILITY);
            if (rows.isEmpty()) {
                return delivered;
            }
            for (Map<String, Object> row : rows) {
                if (++delivered > MAX_DELIVERIES) {
                    throw new IllegalStateException("Inventory event drain did not converge");
                }
                dispatch(row);
            }
        }
    }

    /** Replays one physical Outbox message to verify subscriber-scoped Inbox idempotency. */
    public void redeliver(UUID eventId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
        SELECT id, type, partition_key, payload, headers
          FROM event_outbox
         WHERE id = ?
           AND route = ?
        """, eventId, InventoryChannels.STOCK_EVENTS);
        if (rows.size() != 1) {
            throw new IllegalArgumentException("Inventory Outbox event not found: " + eventId);
        }
        dispatch(rows.getFirst());
    }

    private void dispatch(Map<String, Object> row) {
        UUID eventId = UUID.fromString(row.get("id").toString());
        String eventType = row.get("type").toString();
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                InventoryChannels.STOCK_EVENTS,
                0,
                0,
                row.get("partition_key").toString(),
                row.get("payload").toString());
        record.headers()
                .add(KafkaMessageMapper.LEGACY_ID_HEADER, eventId.toString().getBytes(StandardCharsets.UTF_8));
        record.headers().add(KafkaMessageMapper.LEGACY_EVENT_TYPE_HEADER, eventType.getBytes(StandardCharsets.UTF_8));
        record.headers()
                .add(
                        KafkaMessageMapper.SERIALIZED_HEADERS,
                        row.get("headers").toString().getBytes(StandardCharsets.UTF_8));
        emitter.accept(record.topic(), messageMapper.map(record));
    }
}
