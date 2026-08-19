package com.flowzati.archone.stock.inventory.application.event;

import com.flowzati.archone.stock.inventory.domain.event.StockAvailabilityIncreased;

/** 將庫存可用量增加事實發布到 transactional Integration Event path。 */
@FunctionalInterface
public interface InventoryEventPublisher {

  void publish(StockAvailabilityIncreased event);
}
