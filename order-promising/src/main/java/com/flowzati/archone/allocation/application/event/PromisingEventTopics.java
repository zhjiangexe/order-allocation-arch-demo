package com.flowzati.archone.allocation.application.event;

/**
 * Allocation 進出的 Kafka topic。
 *
 * <p>兩者方向相反，刻意放在一起：這個 package 裡的 integration event 類別本來就同時包含
 * 發布的（{@link OrderAllocatedIntegrationEvent}、{@link BackorderCreatedIntegrationEvent}）
 * 與訂閱的（{@link StockReplenishedIntegrationEvent}），topic 常數跟著事件走即可。
 *
 * <p>{@link InventoryEventTopics#STOCK_EVENTS} 沒有真正的擁有者——它由外部的庫存系統發布，本專案裡是 dev-only
 * 的補貨探針在模擬。它放在這裡是因為對應的事件類別在這裡，而那是因為 allocation 是唯一
 * 關心它的一方；等真的有 Inventory context，這個常數該跟著搬過去。
 */
public final class PromisingEventTopics {

  /** Allocation 發布配置與缺貨結果。 */
  public static final String ALLOCATION_EVENTS = "promising.allocation-events";

  private PromisingEventTopics() {
  }
}
