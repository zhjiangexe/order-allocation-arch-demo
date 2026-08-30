package com.flowzati.archone.inventory.adapter;

import com.flowzati.archone.inventory.movement.application.command.CompleteOutboundMovementsCommand;
import com.flowzati.archone.inventory.movement.application.usecase.CompleteOutboundMovementsUsecase;
import com.flowzati.archone.inventory.reservation.application.command.AllocateOrderCommand;
import com.flowzati.archone.inventory.reservation.application.usecase.AllocateOrderUsecase;
import com.flowzati.archone.orderfulfillment.contract.activity.inventory.CompleteOutboundMovementsActivityInput;
import com.flowzati.archone.orderfulfillment.contract.activity.inventory.InventoryActivities;
import com.flowzati.archone.orderfulfillment.contract.activity.inventory.RequestAllocationActivityInput;

/** Temporal Activity contract 到 Inventory application use cases 的 inbound adapter。 */
public final class TemporalInventoryActivitiesAdapter implements InventoryActivities {

    private final AllocateOrderUsecase allocateOrderUsecase;
    private final CompleteOutboundMovementsUsecase completeOutboundMovements;

    public TemporalInventoryActivitiesAdapter(
            AllocateOrderUsecase allocateOrderUsecase, CompleteOutboundMovementsUsecase completeOutboundMovements) {
        this.allocateOrderUsecase = allocateOrderUsecase;
        this.completeOutboundMovements = completeOutboundMovements;
    }

    @Override
    public void requestAllocation(RequestAllocationActivityInput input) {
        // Temporal 與事件模式共用同一個 Inventory application flow。
        allocateOrderUsecase.execute(new AllocateOrderCommand(input.orderId()));
    }

    @Override
    public void completeOutboundMovements(CompleteOutboundMovementsActivityInput input) {
        completeOutboundMovements.execute(new CompleteOutboundMovementsCommand(
                input.orderId(),
                input.shipmentId(),
                input.stockOperationId(),
                input.movementIds(),
                input.handedOverAt()));
    }
}
