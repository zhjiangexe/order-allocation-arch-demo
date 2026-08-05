package com.flowzati.archone.stock.application.event;

/**
 * Allocation 進出的 Kafka topic。
 *
 * <p>這個 package 裡由 Promising 發布的配置結果事件共用同一條 topic。
 */
public final class PromisingEventTopics {

  /** Allocation 發布配置與缺貨結果。 */
  public static final String ALLOCATION_EVENTS = "promising.allocation-events";

  private PromisingEventTopics() {
  }
}
