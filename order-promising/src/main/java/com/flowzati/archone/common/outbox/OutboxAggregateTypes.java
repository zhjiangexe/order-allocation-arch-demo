package com.flowzati.archone.common.outbox;

public final class OutboxAggregateTypes {

  public static final String ORDER = "Order";

  /** 續做喚醒的 aggregate 是那批庫存，不是任何一張訂單——它不屬於佇列裡的任何一張單。 */
  public static final String STOCK_POOL = "StockPool";

  private OutboxAggregateTypes() {
  }
}
