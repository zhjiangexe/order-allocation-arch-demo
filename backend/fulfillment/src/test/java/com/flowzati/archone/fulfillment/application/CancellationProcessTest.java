package com.flowzati.archone.fulfillment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowzati.archone.fulfillment.application.enums.CancellationProcessState;
import com.flowzati.archone.fulfillment.application.enums.OrderingCancellationOutcome;
import com.flowzati.archone.fulfillment.application.enums.WmsCancellationOutcome;
import com.flowzati.archone.fulfillment.application.invocation.OrderingCancellationOutcomeCommand;
import com.flowzati.archone.fulfillment.application.invocation.WmsCancellationOutcomeCommand;
import com.flowzati.archone.fulfillment.application.state.CancellationProcess;
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
                        .onWmsOutcome(wms(WmsCancellationOutcome.NO_SHIPMENT)))
                .contains(CancellationProcessState.WAITING_ORDERING);
        assertThat(process(CancellationProcessState.WAITING_WMS, null, null)
                        .onWmsOutcome(wms(WmsCancellationOutcome.REJECTED)))
                .contains(CancellationProcessState.REJECTED);
        assertThat(process(CancellationProcessState.WAITING_WMS, null, null)
                        .onWmsOutcome(wms(WmsCancellationOutcome.MULTIPLE_SHIPMENTS)))
                .contains(CancellationProcessState.CONFLICT);
    }

    @Test
    void repeatedWmsOutcomeIsIgnoredButContradictoryOutcomeFails() {
        CancellationProcess process =
                process(CancellationProcessState.WAITING_ORDERING, WmsCancellationOutcome.NO_SHIPMENT, null);
        assertThat(process.onWmsOutcome(wms(WmsCancellationOutcome.NO_SHIPMENT)))
                .isEmpty();
        assertThatThrownBy(() -> process.onWmsOutcome(wms(WmsCancellationOutcome.SHIPMENT_CANCELLED)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Contradictory WMS");
    }

    @Test
    void mismatchedWmsPayloadCannotAdvanceProcess() {
        CancellationProcess process = process(CancellationProcessState.WAITING_WMS, null, null);
        assertThatThrownBy(() -> process.onWmsOutcome(new WmsCancellationOutcomeCommand(
                        requestId, orderId, requestedAt, "another reason", WmsCancellationOutcome.NO_SHIPMENT)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not match request");
    }

    @Test
    void orderingOutcomeCompletesOrConflicts() {
        CancellationProcess process =
                process(CancellationProcessState.WAITING_ORDERING, WmsCancellationOutcome.NO_SHIPMENT, null);
        assertThat(process.onOrderingOutcome(ordering(OrderingCancellationOutcome.SUCCEEDED)))
                .contains(CancellationProcessState.COMPLETED);
        assertThat(process.onOrderingOutcome(ordering(OrderingCancellationOutcome.REJECTED)))
                .contains(CancellationProcessState.CONFLICT);
    }

    @Test
    void repeatedOrderingOutcomeIsIgnoredButContradictoryOutcomeFails() {
        CancellationProcess process = process(
                CancellationProcessState.COMPLETED,
                WmsCancellationOutcome.NO_SHIPMENT,
                OrderingCancellationOutcome.SUCCEEDED);
        assertThat(process.onOrderingOutcome(ordering(OrderingCancellationOutcome.SUCCEEDED)))
                .isEmpty();
        assertThatThrownBy(() -> process.onOrderingOutcome(ordering(OrderingCancellationOutcome.REJECTED)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Contradictory Ordering");
    }

    private WmsCancellationOutcomeCommand wms(WmsCancellationOutcome outcome) {
        return new WmsCancellationOutcomeCommand(requestId, orderId, requestedAt, "customer request", outcome);
    }

    private OrderingCancellationOutcomeCommand ordering(OrderingCancellationOutcome outcome) {
        return new OrderingCancellationOutcomeCommand(requestId, orderId, outcome);
    }

    private CancellationProcess process(
            CancellationProcessState state,
            WmsCancellationOutcome wmsOutcome,
            OrderingCancellationOutcome orderingOutcome) {
        return new CancellationProcess(
                requestId, orderId, requestedAt, "customer request", state, wmsOutcome, orderingOutcome);
    }
}
