package com.flowzati.archone.inventory.allocation.entrypoint.temporal;

import com.flowzati.archone.inventory.allocation.application.invocation.AllocateOrderCommand;
import com.flowzati.archone.inventory.allocation.application.usecase.AllocateOrderUsecase;
import com.flowzati.archone.orchestration.contract.activity.inventory.InventoryAllocationActivities;
import com.flowzati.archone.orchestration.contract.activity.inventory.RequestAllocationActivityInput;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Temporal Inventory Allocation Activity contract 到 Allocation application use case 的 inbound adapter。 */
@Component
@ConditionalOnProperty(name = "archone.fulfillment.orchestration-mode", havingValue = "temporal")
public final class TemporalInventoryAllocationActivitiesAdapter implements InventoryAllocationActivities {

    private final AllocateOrderUsecase allocateOrderUsecase;

    public TemporalInventoryAllocationActivitiesAdapter(AllocateOrderUsecase allocateOrderUsecase) {
        this.allocateOrderUsecase = allocateOrderUsecase;
    }

    @Override
    public void requestAllocation(RequestAllocationActivityInput input) {
        // Temporal 與事件模式共用同一個 Inventory application flow。
        allocateOrderUsecase.execute(new AllocateOrderCommand(input.orderId()));
    }
}
