package com.flowzati.archone.inventory.movement.entrypoint.temporal;

import com.flowzati.archone.foundation.configuration.FulfillmentOrchestrationMode;
import com.flowzati.archone.foundation.simulation.SimulationUtil;
import com.flowzati.archone.inventory.movement.application.invocation.CompleteOutboundMovementsCommand;
import com.flowzati.archone.inventory.movement.application.usecase.CompleteOutboundMovementsUsecase;
import com.flowzati.archone.orchestration.contract.activity.inventory.CompleteOutboundMovementsActivityInput;
import com.flowzati.archone.orchestration.contract.activity.inventory.InventoryMovementActivities;
import io.temporal.activity.Activity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Temporal Inventory Movement Activity contract 到 Movement application use case 的 inbound adapter。 */
@Component
@ConditionalOnProperty(
        name = FulfillmentOrchestrationMode.ORCHESTRATION_MODE,
        havingValue = FulfillmentOrchestrationMode.TEMPORAL)
public final class TemporalInventoryMovementActivitiesAdapter implements InventoryMovementActivities {

    private final CompleteOutboundMovementsUsecase completeOutboundMovementsUsecase;
    private final boolean simulateRetryOnce;

    /** Test-friendly constructor with retry simulation disabled. */
    public TemporalInventoryMovementActivitiesAdapter(
            CompleteOutboundMovementsUsecase completeOutboundMovementsUsecase) {
        this(completeOutboundMovementsUsecase, false);
    }

    @Autowired
    public TemporalInventoryMovementActivitiesAdapter(
            CompleteOutboundMovementsUsecase completeOutboundMovementsUsecase,
            @Value("${archone.temporal.simulate-retry-once:true}") boolean simulateRetryOnce) {
        this.completeOutboundMovementsUsecase = completeOutboundMovementsUsecase;
        this.simulateRetryOnce = simulateRetryOnce;
    }

    @Override
    public void completeOutboundMovements(CompleteOutboundMovementsActivityInput input) {
        if (simulateRetryOnce && Activity.getExecutionContext().getInfo().getAttempt() <= 4) {
            throw new IllegalStateException("Simulated retry for completeOutboundMovements activity");
        }
        SimulationUtil.sleep(3_000);
        completeOutboundMovementsUsecase.execute(new CompleteOutboundMovementsCommand(
                input.orderId(),
                input.shipmentId(),
                input.stockOperationId(),
                input.movementIds(),
                input.handedOverAt()));
    }
}
