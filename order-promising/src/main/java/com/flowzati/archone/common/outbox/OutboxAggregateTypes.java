package com.flowzati.archone.common.outbox;

public final class OutboxAggregateTypes {

  public static final String ORDER = "Order";

  /** 庫存可用性事件屬於 StockPool scope，不屬於任何一張訂單。 */
  public static final String STOCK_POOL = "StockPool";

  private OutboxAggregateTypes() {
  }
}
