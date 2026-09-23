package com.flowzati.archone.fulfillment.application.usecase;

import com.flowzati.archone.fulfillment.application.event.CancellationRequestAccepted;
import com.flowzati.archone.fulfillment.application.invocation.FulfillmentCancellationCommand;
import com.flowzati.archone.fulfillment.application.port.CancellationRequestAcceptedPublisher;
import com.flowzati.archone.fulfillment.application.result.FulfillmentCancellationResult;
import com.flowzati.archone.fulfillment.application.state.CancellationProcess;
import com.flowzati.archone.fulfillment.application.state.CancellationProcessState;
import com.flowzati.archone.fulfillment.application.state.FulfillmentCancellationStatus;
import com.flowzati.archone.fulfillment.application.store.CancellationProcessStore;
import org.springframework.transaction.annotation.Transactional;

/** Persists the outbound request in the outbox before acknowledging the caller. */
public class AcceptCancellationRequestUsecase {

    private final CancellationRequestAcceptedPublisher publisher;
    private final CancellationProcessStore store;

    public AcceptCancellationRequestUsecase(
            CancellationRequestAcceptedPublisher publisher, CancellationProcessStore store) {
        this.publisher = publisher;
        this.store = store;
    }

    @Transactional
    public FulfillmentCancellationResult accept(FulfillmentCancellationCommand command) {
        CancellationProcess process = new CancellationProcess(
                command.requestId(),
                command.orderId(),
                command.requestedAt(),
                command.reason(),
                CancellationProcessState.WAITING_WMS);
        if (!store.insert(process)) {
            CancellationProcess existing = store.find(command.requestId()).orElse(null);
            if (existing == null || !existing.sameRequest(command)) {
                return result(FulfillmentCancellationStatus.CONFLICT, command);
            }
            return result(FulfillmentCancellationStatus.ALREADY_REQUESTED, command);
        }
        publisher.publish(new CancellationRequestAccepted(
                command.requestId(), command.orderId(), command.requestedAt(), command.reason()));
        return result(FulfillmentCancellationStatus.ACCEPTED, command);
    }

    private static FulfillmentCancellationResult result(
            FulfillmentCancellationStatus status, FulfillmentCancellationCommand command) {
        return new FulfillmentCancellationResult(status, command.requestId());
    }
}
