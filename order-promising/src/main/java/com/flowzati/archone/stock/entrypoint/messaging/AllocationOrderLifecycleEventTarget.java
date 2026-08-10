package com.flowzati.archone.stock.entrypoint.messaging;

import com.flowzati.archone.contracts.ordering.v1.OrderCancelledIntegrationEvent;
import com.flowzati.archone.contracts.ordering.v1.OrderPlacedIntegrationEvent;
import com.flowzati.archone.stock.application.command.AllocateOrderCommand;
import com.flowzati.archone.stock.application.command.CancelMovementsCommand;
import com.flowzati.archone.stock.application.usecase.AllocateOrderUsecase;
import com.flowzati.archone.stock.application.usecase.CancelMovementsUsecase;
import org.springframework.stereotype.Component;

/** Allocation-owned target for order lifecycle facts received from Ordering. */
@Component
public final class AllocationOrderLifecycleEventTarget {

  private final AllocateOrderUsecase allocateOrderUsecase;
  private final CancelMovementsUsecase cancelMovementsUsecase;

  public AllocationOrderLifecycleEventTarget(
      AllocateOrderUsecase allocateOrderUsecase,
      CancelMovementsUsecase cancelMovementsUsecase
  ) {
    this.allocateOrderUsecase = allocateOrderUsecase;
    this.cancelMovementsUsecase = cancelMovementsUsecase;
  }

  public void onOrderPlaced(OrderPlacedIntegrationEvent event) {
    allocateOrderUsecase.execute(new AllocateOrderCommand(event.getOrderId()));
  }

  public void onOrderCancelled(OrderCancelledIntegrationEvent event) {
    cancelMovementsUsecase.execute(new CancelMovementsCommand(event.getOrderId()));
  }
}
