package com.flowzati.archone.ordering.entrypoint.kafka;

import com.flowzati.archone.ordering.application.event.OrderingEventSubscriptions;
import com.flowzati.archone.stock.application.event.PromisingEventTopics;
import com.flowzati.archone.messaging.kafka.KafkaIntegrationEventDispatcher;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * ordering 消費配貨結果。
 *
 * <p>{@code promising.allocation-events} 上的事件一直在發，但在此之前沒有任何 consumer——
 * 配貨結果既走事件、又走同一交易內對 {@code Order} 的直接寫入，同一件事有兩條路徑陳述而只有
 * 一條被消費。現在只剩事件這一條。
 */
@Component
public class OrderingKafkaIntegrationEventConsumer {

  private final KafkaIntegrationEventDispatcher dispatcher;

  public OrderingKafkaIntegrationEventConsumer(KafkaIntegrationEventDispatcher dispatcher) {
    this.dispatcher = dispatcher;
  }

  @KafkaListener(
      id = OrderingEventSubscriptions.ALLOCATION_RESULTS,
      topics = PromisingEventTopics.ALLOCATION_EVENTS)
  public void consumeAllocationEvent(ConsumerRecord<String, String> record) {
    dispatcher.dispatch(
        record,
        PromisingEventTopics.ALLOCATION_EVENTS,
        OrderingEventSubscriptions.ALLOCATION_RESULTS);
  }
}
