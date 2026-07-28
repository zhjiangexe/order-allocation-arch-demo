package com.flowzati.archone.ordering.application.event;

/**
 * Ordering 對外發布的 Kafka topic。
 *
 * <p>與 {@link OrderPlacedIntegrationEvent}、{@link OrderCancelledIntegrationEvent} 同處一個
 * package，因為它們是同一份契約的兩半：事件的形狀與它出現在哪裡。之前常數放在中立的
 * {@code common.messaging}，等於讓契約的一半沒有擁有者——訂閱方明確地 import 事件類別，卻要
 * 從一個共用袋子拿 topic 名稱。
 *
 * <p>訂閱方（目前是 allocation）import 這裡是正確的方向：契約由發布方定義。
 */
public final class OrderingEventTopics {

  public static final String ORDER_EVENTS = "ordering.order-events";

  private OrderingEventTopics() {
  }
}
