package com.flowzati.archone.ordering.entrypoint.messaging;

import com.flowzati.archone.contracts.promising.v1.BackorderCreatedIntegrationEvent;
import com.flowzati.archone.contracts.promising.v1.OrderAllocatedIntegrationEvent;
import com.flowzati.archone.ordering.application.command.RecordOrderAllocationCommand;
import com.flowzati.archone.ordering.application.command.RecordOrderBackorderCommand;
import com.flowzati.archone.ordering.application.usecase.RecordOrderAllocationUsecase;
import com.flowzati.archone.ordering.application.usecase.RecordOrderBackorderUsecase;
import org.springframework.stereotype.Component;

/**
 * Ordering-owned target for allocation outcomes received from Promising.
 *
 * <p>The target owns event-to-command translation once. Its Tram-shaped handler group delegates
 * here, keeping broker and subscriber metadata outside the application use cases.
 */
@Component
public final class OrderingAllocationResultEventTarget {

  private final RecordOrderAllocationUsecase recordOrderAllocationUsecase;
  private final RecordOrderBackorderUsecase recordOrderBackorderUsecase;

  public OrderingAllocationResultEventTarget(
      RecordOrderAllocationUsecase recordOrderAllocationUsecase,
      RecordOrderBackorderUsecase recordOrderBackorderUsecase
  ) {
    this.recordOrderAllocationUsecase = recordOrderAllocationUsecase;
    this.recordOrderBackorderUsecase = recordOrderBackorderUsecase;
  }

  public void onOrderAllocated(OrderAllocatedIntegrationEvent event) {
    recordOrderAllocationUsecase.execute(
        new RecordOrderAllocationCommand(event.getOrderId(), event.getAllocatedAt()));
  }

  public void onBackorderCreated(BackorderCreatedIntegrationEvent event) {
    recordOrderBackorderUsecase.execute(
        new RecordOrderBackorderCommand(event.getOrderId(), event.getBackorderedSince()));
  }
}
