package com.flowzati.archone.orderfulfillment.application.usecase;

import com.flowzati.archone.orderfulfillment.application.CancellationProcess;
import com.flowzati.archone.orderfulfillment.application.CancellationProcessState;
import com.flowzati.archone.orderfulfillment.application.FulfillmentCancellationStatus;
import com.flowzati.archone.orderfulfillment.application.event.CancellationRequestAccepted;
import com.flowzati.archone.orderfulfillment.application.invocation.FulfillmentCancellationCommand;
import com.flowzati.archone.orderfulfillment.application.port.CancellationProcessStore;
import com.flowzati.archone.orderfulfillment.application.port.CancellationRequestAcceptedPublisher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Persists the outbound request in the outbox before acknowledging the caller. */
@Service
@ConditionalOnProperty(name = "archone.fulfillment.orchestration-mode", havingValue = "events", matchIfMissing = true)
public class AcceptCancellationRequestUsecase {

    private final CancellationRequestAcceptedPublisher publisher;
    private final CancellationProcessStore store;

    public AcceptCancellationRequestUsecase(
            CancellationRequestAcceptedPublisher publisher, CancellationProcessStore store) {
        this.publisher = publisher;
        this.store = store;
    }

    @Transactional
    public FulfillmentCancellationStatus accept(FulfillmentCancellationCommand command) {
        CancellationProcess process = new CancellationProcess(
                command.requestId(),
                command.orderId(),
                command.requestedAt(),
                command.reason(),
                CancellationProcessState.WAITING_WMS);
        if (!store.insert(process)) {
            CancellationProcess existing = store.find(command.requestId()).orElse(null);
            if (existing == null || !existing.sameRequest(command)) {
                return FulfillmentCancellationStatus.CONFLICT;
            }
            return FulfillmentCancellationStatus.ALREADY_REQUESTED;
        }
        publisher.publish(new CancellationRequestAccepted(
                command.requestId(), command.orderId(), command.requestedAt(), command.reason()));
        return FulfillmentCancellationStatus.ACCEPTED;
    }
}
