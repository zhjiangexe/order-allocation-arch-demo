package com.flowzati.archone.orderfulfillment.application.usecase;

import com.flowzati.archone.orderfulfillment.application.CancellationProcess;
import com.flowzati.archone.orderfulfillment.application.CancellationProcessState;
import com.flowzati.archone.orderfulfillment.application.event.OrderingCancellationRequested;
import com.flowzati.archone.orderfulfillment.application.invocation.WmsCancellationOutcomeCommand;
import com.flowzati.archone.orderfulfillment.application.port.CancellationProcessStore;
import com.flowzati.archone.orderfulfillment.application.port.OrderingCancellationRequestedPublisher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name = "archone.fulfillment.orchestration-mode", havingValue = "events", matchIfMissing = true)
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
