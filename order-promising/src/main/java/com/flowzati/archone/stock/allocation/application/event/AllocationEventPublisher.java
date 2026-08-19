package com.flowzati.archone.stock.allocation.application.event;

import com.flowzati.archone.stock.allocation.domain.event.OrderAllocationCompleted;

/** 將配貨完成事實發布到 transactional Integration Event path。 */
@FunctionalInterface
public interface AllocationEventPublisher {

  void publish(OrderAllocationCompleted event);
}
