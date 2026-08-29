package com.flowzati.archone.inventory.adapter;

import com.flowzati.archone.inventory.movement.application.usecase.CompleteStockOperationUsecase;
import com.flowzati.archone.inventory.reservation.application.command.AllocateOrderCommand;
import com.flowzati.archone.inventory.reservation.application.usecase.AllocateOrderUsecase;
import com.flowzati.archone.orderfulfillment.contract.activity.inventory.CompleteOutboundMovementsActivityInput;
import com.flowzati.archone.orderfulfillment.contract.activity.inventory.InventoryActivities;
import com.flowzati.archone.orderfulfillment.contract.activity.inventory.RequestAllocationActivityInput;

/** Temporal Activity contract 到 Inventory application use cases 的 inbound adapter。 */
public final class TemporalInventoryActivitiesAdapter implements InventoryActivities {

    private final AllocateOrderUsecase allocateOrderUsecase;
    private final CompleteStockOperationUsecase completeStockOperation;

    public TemporalInventoryActivitiesAdapter(
            AllocateOrderUsecase allocateOrderUsecase, CompleteStockOperationUsecase completeStockOperation) {
        this.allocateOrderUsecase = allocateOrderUsecase;
        this.completeStockOperation = completeStockOperation;
    }

    @Override
    public void requestAllocation(RequestAllocationActivityInput input) {
        // Temporal 與事件模式共用同一個 Inventory application flow。
        allocateOrderUsecase.execute(new AllocateOrderCommand(input.orderId()));
    }

    @Override
    public void completeOutboundMovements(CompleteOutboundMovementsActivityInput input) {
        completeStockOperation.execute(input.stockOperationId(), input.handedOverAt());
    }
}
