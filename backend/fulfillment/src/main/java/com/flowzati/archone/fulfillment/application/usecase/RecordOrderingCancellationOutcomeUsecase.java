package com.flowzati.archone.fulfillment.application.usecase;

import com.flowzati.archone.foundation.configuration.FulfillmentOrchestrationMode;
import com.flowzati.archone.fulfillment.application.invocation.OrderingCancellationOutcomeCommand;
import com.flowzati.archone.fulfillment.application.state.CancellationProcess;
import com.flowzati.archone.fulfillment.application.store.CancellationProcessStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(
        name = FulfillmentOrchestrationMode.ORCHESTRATION_MODE,
        havingValue = FulfillmentOrchestrationMode.EVENTS,
        matchIfMissing = true)
public class RecordOrderingCancellationOutcomeUsecase {
    private final CancellationProcessStore store;

    public RecordOrderingCancellationOutcomeUsecase(CancellationProcessStore store) {
        this.store = store;
    }

    @Transactional
    public void record(OrderingCancellationOutcomeCommand command) {
        CancellationProcess process = store.lock(command.requestId())
                .orElseThrow(() -> new IllegalStateException("Cancellation process not found: " + command.requestId()));
        process.onOrderingOutcome(command)
                .ifPresent(next -> store.recordOrderingOutcome(process.requestId(), next, command.outcome()));
    }
}
