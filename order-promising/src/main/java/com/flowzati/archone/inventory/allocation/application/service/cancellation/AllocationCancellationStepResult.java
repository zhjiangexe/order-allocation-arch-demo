package com.flowzati.archone.inventory.allocation.application.service.cancellation;

import com.flowzati.archone.inventory.allocation.domain.aggregate.AllocationDemand;
import com.flowzati.archone.inventory.allocation.domain.type.AllocationCancellationState;

/** 每個短 cancellation transaction 完成後，回傳最新 demand 與流程狀態。 */
public record AllocationCancellationStepResult(AllocationDemand demand, AllocationCancellationState state) {
    public AllocationCancellationStepResult {
        if (demand == null || state == null) {
            throw new IllegalArgumentException("Cancellation step result requires demand and state");
        }
    }
}
