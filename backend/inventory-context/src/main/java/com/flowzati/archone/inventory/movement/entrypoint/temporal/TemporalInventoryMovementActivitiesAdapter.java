package com.flowzati.archone.inventory.movement.entrypoint.temporal;

import com.flowzati.archone.inventory.movement.application.invocation.CompleteOutboundMovementsCommand;
import com.flowzati.archone.inventory.movement.application.usecase.CompleteOutboundMovementsUsecase;
import com.flowzati.archone.orchestration.contract.activity.inventory.CompleteOutboundMovementsActivityInput;
import com.flowzati.archone.orchestration.contract.activity.inventory.InventoryMovementActivities;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Temporal Inventory Movement Activity contract 到 Movement application use case 的 inbound adapter。 */
@Component
@ConditionalOnProperty(name = "archone.fulfillment.orchestration-mode", havingValue = "temporal")
public final class TemporalInventoryMovementActivitiesAdapter implements InventoryMovementActivities {

    private final CompleteOutboundMovementsUsecase completeOutboundMovementsUsecase;

    public TemporalInventoryMovementActivitiesAdapter(
            CompleteOutboundMovementsUsecase completeOutboundMovementsUsecase) {
        this.completeOutboundMovementsUsecase = completeOutboundMovementsUsecase;
    }

    @Override
    public void completeOutboundMovements(CompleteOutboundMovementsActivityInput input) {
        completeOutboundMovementsUsecase.execute(new CompleteOutboundMovementsCommand(
                input.orderId(),
                input.shipmentId(),
                input.stockOperationId(),
                input.movementIds(),
                input.handedOverAt()));
    }
}
