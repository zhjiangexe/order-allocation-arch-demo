package com.flowzati.archone.contracts.ordering.v1;

/** Ordering events 寫入 Outbox 時使用的 stable aggregate type。 */
public final class OrderingAggregateTypes {

  public static final String ORDER = "Order";

  private OrderingAggregateTypes() {
  }
}
