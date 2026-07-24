package com.flowzati.archone.allocation.entrypoint.kafka;

import com.flowzati.archone.common.messaging.kafka.KafkaIntegrationEventDispatcher;
import com.flowzati.archone.common.outbox.OutboxRoutes;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class AllocationKafkaIntegrationEventConsumer {

  private final KafkaIntegrationEventDispatcher dispatcher;

  public AllocationKafkaIntegrationEventConsumer(KafkaIntegrationEventDispatcher dispatcher) {
    this.dispatcher = dispatcher;
  }

  @KafkaListener(id = "allocation-ordering-events", topics = OutboxRoutes.ORDERING_ORDER_EVENTS)
  public void consumeOrderingEvent(ConsumerRecord<String, String> record) {
    dispatcher.dispatch(record, OutboxRoutes.ORDERING_ORDER_EVENTS);
  }

  @KafkaListener(id = "allocation-inventory-events", topics = OutboxRoutes.INVENTORY_STOCK_EVENTS)
  public void consumeInventoryEvent(ConsumerRecord<String, String> record) {
    dispatcher.dispatch(record, OutboxRoutes.INVENTORY_STOCK_EVENTS);
  }
}
