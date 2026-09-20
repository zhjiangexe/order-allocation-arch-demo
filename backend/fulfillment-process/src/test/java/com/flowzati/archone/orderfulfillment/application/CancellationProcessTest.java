package com.flowzati.archone.orderfulfillment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowzati.archone.orderfulfillment.application.invocation.OrderingCancellationOutcomeCommand;
import com.flowzati.archone.orderfulfillment.application.invocation.WmsCancellationOutcomeCommand;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CancellationProcessTest {
    private final UUID requestId = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();
    private final Instant requestedAt = Instant.parse("2026-09-20T10:00:00Z");

    @Test
    void wmsOutcomesDetermineTheNextProcessState() {
        assertThat(process(CancellationProcessState.WAITING_WMS, null, null)
                        .onWmsOutcome(wms(WmsCancellationOutcomeCommand.Outcome.NO_SHIPMENT)))
                .contains(CancellationProcessState.WAITING_ORDERING);
        assertThat(process(CancellationProcessState.WAITING_WMS, null, null)
                        .onWmsOutcome(wms(WmsCancellationOutcomeCommand.Outcome.REJECTED)))
                .contains(CancellationProcessState.REJECTED);
        assertThat(process(CancellationProcessState.WAITING_WMS, null, null)
                        .onWmsOutcome(wms(WmsCancellationOutcomeCommand.Outcome.MULTIPLE_SHIPMENTS)))
                .contains(CancellationProcessState.CONFLICT);
    }

    @Test
    void repeatedWmsOutcomeIsIgnoredButContradictoryOutcomeFails() {
        CancellationProcess process = process(
                CancellationProcessState.WAITING_ORDERING, WmsCancellationOutcomeCommand.Outcome.NO_SHIPMENT, null);
        assertThat(process.onWmsOutcome(wms(WmsCancellationOutcomeCommand.Outcome.NO_SHIPMENT)))
                .isEmpty();
        assertThatThrownBy(() -> process.onWmsOutcome(wms(WmsCancellationOutcomeCommand.Outcome.SHIPMENT_CANCELLED)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Contradictory WMS");
    }

    @Test
    void mismatchedWmsPayloadCannotAdvanceProcess() {
        CancellationProcess process = process(CancellationProcessState.WAITING_WMS, null, null);
        assertThatThrownBy(() -> process.onWmsOutcome(new WmsCancellationOutcomeCommand(
                        requestId,
                        orderId,
                        requestedAt,
                        "another reason",
                        WmsCancellationOutcomeCommand.Outcome.NO_SHIPMENT)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not match request");
    }

    @Test
    void orderingOutcomeCompletesOrConflicts() {
        CancellationProcess process = process(
                CancellationProcessState.WAITING_ORDERING, WmsCancellationOutcomeCommand.Outcome.NO_SHIPMENT, null);
        assertThat(process.onOrderingOutcome(ordering(OrderingCancellationOutcomeCommand.Outcome.SUCCEEDED)))
                .contains(CancellationProcessState.COMPLETED);
        assertThat(process.onOrderingOutcome(ordering(OrderingCancellationOutcomeCommand.Outcome.REJECTED)))
                .contains(CancellationProcessState.CONFLICT);
    }

    @Test
    void repeatedOrderingOutcomeIsIgnoredButContradictoryOutcomeFails() {
        CancellationProcess process = process(
                CancellationProcessState.COMPLETED,
                WmsCancellationOutcomeCommand.Outcome.NO_SHIPMENT,
                OrderingCancellationOutcomeCommand.Outcome.SUCCEEDED);
        assertThat(process.onOrderingOutcome(ordering(OrderingCancellationOutcomeCommand.Outcome.SUCCEEDED)))
                .isEmpty();
        assertThatThrownBy(
                        () -> process.onOrderingOutcome(ordering(OrderingCancellationOutcomeCommand.Outcome.REJECTED)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Contradictory Ordering");
    }

    private WmsCancellationOutcomeCommand wms(WmsCancellationOutcomeCommand.Outcome outcome) {
        return new WmsCancellationOutcomeCommand(requestId, orderId, requestedAt, "customer request", outcome);
    }

    private OrderingCancellationOutcomeCommand ordering(OrderingCancellationOutcomeCommand.Outcome outcome) {
        return new OrderingCancellationOutcomeCommand(requestId, orderId, outcome);
    }

    private CancellationProcess process(
            CancellationProcessState state,
            WmsCancellationOutcomeCommand.Outcome wmsOutcome,
            OrderingCancellationOutcomeCommand.Outcome orderingOutcome) {
        return new CancellationProcess(
                requestId, orderId, requestedAt, "customer request", state, wmsOutcome, orderingOutcome);
    }
}
