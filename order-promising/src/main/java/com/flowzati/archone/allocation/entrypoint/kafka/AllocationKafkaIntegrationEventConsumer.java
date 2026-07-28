package com.flowzati.archone.allocation.entrypoint.kafka;

import com.flowzati.archone.allocation.application.event.InventoryEventTopics;
import com.flowzati.archone.common.messaging.kafka.KafkaIntegrationEventDispatcher;
import com.flowzati.archone.ordering.application.event.OrderingEventTopics;
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
  @KafkaListener(id = "allocation-ordering-events", topics = OrderingEventTopics.ORDER_EVENTS)
  public void consumeOrderingEvent(ConsumerRecord<String, String> record) {
    dispatcher.dispatch(record, OrderingEventTopics.ORDER_EVENTS);
  }

  /** 補貨事件走同一套重試機制，見 {@link #consumeOrderingEvent}。 */
  @KafkaListener(id = "allocation-inventory-events", topics = InventoryEventTopics.STOCK_EVENTS)
  public void consumeInventoryEvent(ConsumerRecord<String, String> record) {
    dispatcher.dispatch(record, InventoryEventTopics.STOCK_EVENTS);
  }
}
