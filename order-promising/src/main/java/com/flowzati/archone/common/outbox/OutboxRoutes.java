package com.flowzati.archone.common.outbox;

public final class OutboxRoutes {

  public static final String ORDERING_ORDER_EVENTS = "ordering.order-events";
  public static final String INVENTORY_STOCK_EVENTS = "inventory.stock-events";
  public static final String PROMISING_ALLOCATION_EVENTS = "promising.allocation-events";

  private OutboxRoutes() {
  }
}
