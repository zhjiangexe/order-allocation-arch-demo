package com.flowzati.archone.inventory.allocation.application.event;

import com.flowzati.archone.inventory.allocation.domain.event.OrderAllocationCompleted;

/** 將配貨完成事實發布到 transactional Integration Event path。 */
@FunctionalInterface
public interface AllocationEventPublisher {

    void publish(OrderAllocationCompleted event);
}
