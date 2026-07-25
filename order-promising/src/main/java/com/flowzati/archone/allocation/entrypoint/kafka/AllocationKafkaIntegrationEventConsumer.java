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

  /** 下面兩個 listener 的 container 層退避／DLT 處理都在 {@code AllocationKafkaErrorHandlingConfiguration}。 */
  @KafkaListener(id = "allocation-ordering-events", topics = IntegrationEventTopics.ORDERING_ORDER_EVENTS_TOPIC)
  public void consumeOrderingEvent(ConsumerRecord<String, String> record) {
    dispatcher.dispatch(record, IntegrationEventTopics.ORDERING_ORDER_EVENTS_TOPIC);
  }

  /** 補貨事件走同一套重試機制，見 {@link #consumeOrderingEvent}。 */
  @KafkaListener(id = "allocation-inventory-events", topics = IntegrationEventTopics.INVENTORY_STOCK_EVENTS_TOPIC)
  public void consumeInventoryEvent(ConsumerRecord<String, String> record) {
    dispatcher.dispatch(record, IntegrationEventTopics.INVENTORY_STOCK_EVENTS_TOPIC);
  }
}
