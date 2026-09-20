package com.flowzati.archone.orderfulfillment.application.usecase;

import com.flowzati.archone.orderfulfillment.application.CancellationProcess;
import com.flowzati.archone.orderfulfillment.application.invocation.OrderingCancellationOutcomeCommand;
import com.flowzati.archone.orderfulfillment.application.port.CancellationProcessStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name = "archone.fulfillment.orchestration-mode", havingValue = "events", matchIfMissing = true)
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
