package com.flowzati.archone.inventory.entrypoint.temporal;

import com.flowzati.archone.inventory.allocation.application.command.AllocateOrderCommand;
import com.flowzati.archone.inventory.allocation.application.usecase.AllocateOrderUsecase;
import com.flowzati.archone.inventory.balance.application.command.CompleteOutboundMovementsCommand;
import com.flowzati.archone.inventory.balance.application.usecase.CompleteOutboundMovementsUsecase;
import com.flowzati.archone.orderfulfillment.contract.activity.inventory.CompleteOutboundMovementsActivityInput;
import com.flowzati.archone.orderfulfillment.contract.activity.inventory.InventoryActivities;
import com.flowzati.archone.orderfulfillment.contract.activity.inventory.RequestAllocationActivityInput;

/** Temporal Activity contract 到 Inventory application use cases 的 inbound adapter。 */
public final class TemporalInventoryActivitiesAdapter implements InventoryActivities {

    private final AllocateOrderUsecase allocateOrderUsecase;
    private final CompleteOutboundMovementsUsecase completeOutboundMovementsUsecase;

    public TemporalInventoryActivitiesAdapter(
            AllocateOrderUsecase allocateOrderUsecase,
            CompleteOutboundMovementsUsecase completeOutboundMovementsUsecase) {
        this.allocateOrderUsecase = allocateOrderUsecase;
        this.completeOutboundMovementsUsecase = completeOutboundMovementsUsecase;
    }

    @Override
    public void requestAllocation(RequestAllocationActivityInput input) {
        allocateOrderUsecase.execute(new AllocateOrderCommand(input.orderId()));
    }

    @Override
    public void completeOutboundMovements(CompleteOutboundMovementsActivityInput input) {
        completeOutboundMovementsUsecase.execute(new CompleteOutboundMovementsCommand(
                input.allocationId(), input.orderId(), input.shipmentId(), input.movementIds(), input.handedOverAt()));
    }
}
