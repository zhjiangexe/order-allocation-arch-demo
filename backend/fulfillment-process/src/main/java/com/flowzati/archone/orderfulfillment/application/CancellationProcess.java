package com.flowzati.archone.orderfulfillment.application;

import com.flowzati.archone.orderfulfillment.application.invocation.FulfillmentCancellationCommand;
import com.flowzati.archone.orderfulfillment.application.invocation.OrderingCancellationOutcomeCommand;
import com.flowzati.archone.orderfulfillment.application.invocation.WmsCancellationOutcomeCommand;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

public record CancellationProcess(
        UUID requestId,
        UUID orderId,
        Instant requestedAt,
        String reason,
        CancellationProcessState state,
        WmsCancellationOutcomeCommand.Outcome wmsOutcome,
        OrderingCancellationOutcomeCommand.Outcome orderingOutcome) {

    public CancellationProcess(
            UUID requestId, UUID orderId, Instant requestedAt, String reason, CancellationProcessState state) {
        this(requestId, orderId, requestedAt, reason, state, null, null);
    }

    public boolean sameRequest(FulfillmentCancellationCommand command) {
        return requestId.equals(command.requestId())
                && orderId.equals(command.orderId())
                && sameTime(requestedAt, command.requestedAt())
                && reason.equals(command.reason());
    }

    public Optional<CancellationProcessState> onWmsOutcome(WmsCancellationOutcomeCommand command) {
        if (!requestId.equals(command.requestId())
                || !orderId.equals(command.orderId())
                || !sameTime(requestedAt, command.requestedAt())
                || !reason.equals(command.reason())) {
            throw new IllegalStateException("WMS cancellation result does not match request: " + requestId);
        }
        if (wmsOutcome != null) {
            if (wmsOutcome == command.outcome()) {
                return Optional.empty();
            }
            throw new IllegalStateException("Contradictory WMS cancellation result: " + requestId);
        }
        if (state != CancellationProcessState.WAITING_WMS) {
            throw new IllegalStateException("Unexpected WMS cancellation result: " + requestId);
        }
        return Optional.of(
                switch (command.outcome()) {
                    case SHIPMENT_CANCELLED, NO_SHIPMENT -> CancellationProcessState.WAITING_ORDERING;
                    case REJECTED -> CancellationProcessState.REJECTED;
                    case MULTIPLE_SHIPMENTS -> CancellationProcessState.CONFLICT;
                });
    }

    public Optional<CancellationProcessState> onOrderingOutcome(OrderingCancellationOutcomeCommand command) {
        if (!requestId.equals(command.requestId()) || !orderId.equals(command.orderId())) {
            throw new IllegalStateException("Ordering cancellation result does not match request: " + requestId);
        }
        if (orderingOutcome != null) {
            if (orderingOutcome == command.outcome()) {
                return Optional.empty();
            }
            throw new IllegalStateException("Contradictory Ordering cancellation result: " + requestId);
        }
        if (state != CancellationProcessState.WAITING_ORDERING) {
            throw new IllegalStateException("Unexpected Ordering cancellation result: " + requestId);
        }
        return Optional.of(
                switch (command.outcome()) {
                    case SUCCEEDED -> CancellationProcessState.COMPLETED;
                    case REJECTED -> CancellationProcessState.CONFLICT;
                });
    }

    private static boolean sameTime(Instant left, Instant right) {
        return left.truncatedTo(ChronoUnit.MICROS).equals(right.truncatedTo(ChronoUnit.MICROS));
    }
}
