package com.flowzati.archone.inventory.balance.application.event;

import com.flowzati.archone.inventory.balance.domain.event.OutboundMovementsCompleted;

/** 將 Inventory 出庫完成事實交給可靠的 integration-event adapter。 */
@FunctionalInterface
public interface OutboundMovementEventPublisher {

    void publish(OutboundMovementsCompleted event);
}
