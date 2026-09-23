package com.flowzati.archone.fulfillment.application.usecase;

import com.flowzati.archone.foundation.configuration.FulfillmentOrchestrationMode;
import com.flowzati.archone.fulfillment.application.event.OrderingCancellationRequested;
import com.flowzati.archone.fulfillment.application.invocation.WmsCancellationOutcomeCommand;
import com.flowzati.archone.fulfillment.application.port.OrderingCancellationRequestedPublisher;
import com.flowzati.archone.fulfillment.application.state.CancellationProcess;
import com.flowzati.archone.fulfillment.application.state.CancellationProcessState;
import com.flowzati.archone.fulfillment.application.store.CancellationProcessStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(
        name = FulfillmentOrchestrationMode.ORCHESTRATION_MODE,
        havingValue = FulfillmentOrchestrationMode.EVENTS,
        matchIfMissing = true)
public class HandleWmsCancellationOutcomeUsecase {
    private final CancellationProcessStore store;
    private final OrderingCancellationRequestedPublisher publisher;

    public HandleWmsCancellationOutcomeUsecase(
            CancellationProcessStore store, OrderingCancellationRequestedPublisher publisher) {
        this.store = store;
        this.publisher = publisher;
    }

    @Transactional
    public void handle(WmsCancellationOutcomeCommand command) {
        CancellationProcess process = store.lock(command.requestId())
                .orElseThrow(() -> new IllegalStateException("Cancellation process not found: " + command.requestId()));
        process.onWmsOutcome(command).ifPresent(next -> {
            store.recordWmsOutcome(process.requestId(), next, command.outcome());
            if (next == CancellationProcessState.WAITING_ORDERING) {
                publisher.publish(new OrderingCancellationRequested(
                        process.requestId(), process.orderId(), process.requestedAt(), process.reason()));
            }
        });
    }
}
