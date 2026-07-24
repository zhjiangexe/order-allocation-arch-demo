package com.flowzati.archone.allocation.entrypoint.kafka;

import com.flowzati.archone.common.messaging.kafka.KafkaIntegrationEventDispatcher;
import com.flowzati.archone.common.messaging.IntegrationEventTopics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class AllocationKafkaIntegrationEventConsumer {

  private final KafkaIntegrationEventDispatcher dispatcher;

  public AllocationKafkaIntegrationEventConsumer(KafkaIntegrationEventDispatcher dispatcher) {
    this.dispatcher = dispatcher;
  }

  @KafkaListener(id = "allocation-ordering-events", topics = IntegrationEventTopics.ORDERING_ORDER_EVENTS_TOPIC)
  public void consumeOrderingEvent(ConsumerRecord<String, String> record) {
    dispatcher.dispatch(record, IntegrationEventTopics.ORDERING_ORDER_EVENTS_TOPIC);
  }

  @KafkaListener(id = "allocation-inventory-events", topics = IntegrationEventTopics.INVENTORY_STOCK_EVENTS_TOPIC)
  public void consumeInventoryEvent(ConsumerRecord<String, String> record) {
    dispatcher.dispatch(record, IntegrationEventTopics.INVENTORY_STOCK_EVENTS_TOPIC);
  }
}
