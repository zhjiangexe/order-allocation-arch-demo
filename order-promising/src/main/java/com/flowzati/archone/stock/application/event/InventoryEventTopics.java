package com.flowzati.archone.stock.application.event;

public class InventoryEventTopics {
  /** Allocation 訂閱的補貨事件，來源在本專案之外。 */
  public static final String STOCK_EVENTS = "inventory.stock-events";

  private InventoryEventTopics() {
  }
}
