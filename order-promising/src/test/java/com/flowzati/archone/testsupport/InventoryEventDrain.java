package com.flowzati.archone.testsupport;

import com.flowzati.archone.messaging.kafka.KafkaIntegrationEventDispatcher;
import com.flowzati.archone.stock.application.event.AllocationEventSubscriptions;
import com.flowzati.archone.stock.application.event.InventoryEventTopics;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.jdbc.core.JdbcTemplate;

/** Delivers pending stock Outbox events in SIT where Debezium and Kafka listeners are disabled. */
public final class InventoryEventDrain {

  private static final int MAX_DELIVERIES = 10_000;

  private final JdbcTemplate jdbcTemplate;
  private final KafkaIntegrationEventDispatcher dispatcher;

  public InventoryEventDrain(
      JdbcTemplate jdbcTemplate,
      KafkaIntegrationEventDispatcher dispatcher
  ) {
    this.jdbcTemplate = jdbcTemplate;
    this.dispatcher = dispatcher;
  }

  /** Delivers pending availability events. Later backorder rounds belong to the scheduler. */
  public int drain() {
    int delivered = 0;
    while (true) {
      List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
          SELECT o.id, o.type, o.payload
            FROM event_outbox o
           WHERE o.route = ?
             AND NOT EXISTS (
                   SELECT 1 FROM event_inbox i
                    WHERE i.subscriber_id = ?
                      AND i.event_id = o.id
                 )
           ORDER BY o.timestamp, o.id
          """, InventoryEventTopics.STOCK_EVENTS,
          AllocationEventSubscriptions.INVENTORY_AVAILABILITY);
      if (rows.isEmpty()) {
        return delivered;
      }
      for (Map<String, Object> row : rows) {
        if (++delivered > MAX_DELIVERIES) {
          throw new IllegalStateException("Inventory event drain did not converge");
        }
        dispatch(
            UUID.fromString(row.get("id").toString()),
            row.get("type").toString(),
            row.get("payload").toString());
      }
    }
  }

  private void dispatch(UUID eventId, String eventType, String payload) {
    ConsumerRecord<String, String> record = new ConsumerRecord<>(
        InventoryEventTopics.STOCK_EVENTS, 0, 0, eventId.toString(), payload);
    record.headers().add("id", eventId.toString().getBytes(StandardCharsets.UTF_8));
    record.headers().add("eventType", eventType.getBytes(StandardCharsets.UTF_8));
    dispatcher.dispatch(
        record,
        InventoryEventTopics.STOCK_EVENTS,
        AllocationEventSubscriptions.INVENTORY_AVAILABILITY);
  }
}
