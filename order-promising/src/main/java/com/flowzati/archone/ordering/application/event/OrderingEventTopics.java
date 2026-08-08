package com.flowzati.archone.ordering.application.event;

import com.flowzati.archone.contracts.ordering.v1.OrderCancelledIntegrationEvent;
import com.flowzati.archone.contracts.ordering.v1.OrderPlacedIntegrationEvent;

/**
 * Ordering 對外發布的 Kafka topic。
 *
 * <p>{@link OrderPlacedIntegrationEvent} 與 {@link OrderCancelledIntegrationEvent} 的 payload
 * schema 由 {@code contracts} 擁有；topic 與 partition key 則是 Ordering publisher 的 routing
 * policy，不能混進 framework-level platform infrastructure。
 *
 * <p>訂閱方（目前是 allocation）import 這裡是正確的方向：契約由發布方定義。
 */
public final class OrderingEventTopics {

  public static final String ORDER_EVENTS = "ordering.order-events";

  private OrderingEventTopics() {
  }
}
