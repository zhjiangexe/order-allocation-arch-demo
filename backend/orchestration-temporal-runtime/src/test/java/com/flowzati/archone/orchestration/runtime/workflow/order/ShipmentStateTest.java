package com.flowzati.archone.orchestration.runtime.workflow.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowzati.archone.orchestration.contract.workflow.order.result.ShipmentTerminalStatus;
import io.temporal.failure.ApplicationFailure;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ShipmentStateTest {

    private static final UUID SHIPMENT_ID = UUID.randomUUID();
    private static final Instant OCCURRED_AT = Instant.parse("2026-08-24T10:00:00Z");

    @Test
    void acceptsAnIdempotentReplayOfTheCreatedShipment() {
        ShipmentState state = new ShipmentState();
        state.recordCreated(SHIPMENT_ID);
        state.recordHandover(SHIPMENT_ID, OCCURRED_AT);

        ShipmentTerminalOutcome accepted = state.terminalOutcome();
        state.recordCreated(SHIPMENT_ID);

        assertThat(state.shipmentIdOrNull()).isEqualTo(SHIPMENT_ID);
        assertThat(state.terminalOutcome()).isSameAs(accepted);
    }

    @Test
    void correlatesATerminalFactReportedAfterShipmentCreation() {
        ShipmentState state = new ShipmentState();
        state.recordCreated(SHIPMENT_ID);

        state.recordHandover(SHIPMENT_ID, OCCURRED_AT);

        assertThat(state.shipmentIdOrNull()).isEqualTo(SHIPMENT_ID);
        assertThat(state.terminalOutcome().status()).isEqualTo(ShipmentTerminalStatus.HANDED_OVER);
    }

    @Test
    void correlatesATerminalFactReportedBeforeTheActivityResponse() {
        ShipmentState state = new ShipmentState();
        state.recordHandover(SHIPMENT_ID, OCCURRED_AT);

        assertThat(state.hasHandover()).isFalse();
        assertThat(state.hasCancellation()).isFalse();
        assertThat(state.terminalStatusOrNull()).isNull();

        state.recordCreated(SHIPMENT_ID);

        assertThat(state.hasHandover()).isTrue();
        assertThat(state.hasCancellation()).isFalse();
        assertThat(state.terminalOutcome().status()).isEqualTo(ShipmentTerminalStatus.HANDED_OVER);
    }

    @Test
    void failsInsteadOfWaitingForeverWhenAnEarlyTerminalBelongsToAnotherShipment() {
        ShipmentState state = new ShipmentState();
        UUID reportedShipmentId = UUID.randomUUID();
        state.recordHandover(reportedShipmentId, OCCURRED_AT);
        ShipmentTerminalOutcome accepted = state.terminalOutcome();

        assertThatThrownBy(() -> state.recordCreated(SHIPMENT_ID))
                .isInstanceOf(ApplicationFailure.class)
                .hasMessageContaining("different Shipment before creation completed");

        assertThat(state.shipmentIdOrNull()).isNull();
        assertThat(state.terminalOutcome()).isSameAs(accepted);
        assertThat(state.hasHandover()).isFalse();
        assertThat(state.hasCancellation()).isFalse();
    }

    @Test
    void ignoresAnotherShipmentAfterTheCreatedIdentityIsKnown() {
        ShipmentState state = new ShipmentState();
        state.recordCreated(SHIPMENT_ID);

        state.recordHandover(UUID.randomUUID(), OCCURRED_AT);

        assertThat(state.hasHandover()).isFalse();
        assertThat(state.hasCancellation()).isFalse();
    }

    @Test
    void acceptsAnIdempotentReplayOfTheSameCancellation() {
        ShipmentState state = new ShipmentState();
        state.recordCreated(SHIPMENT_ID);

        state.recordCancelled(SHIPMENT_ID, OCCURRED_AT);
        state.recordCancelled(SHIPMENT_ID, OCCURRED_AT);

        assertThat(state.terminalOutcome().status()).isEqualTo(ShipmentTerminalStatus.CANCELLED);
    }

    @Test
    void recognizesCancellationOnlyAfterTheShipmentIdentityIsConfirmed() {
        ShipmentState state = new ShipmentState();
        assertThat(state.hasCancellation()).isFalse();

        state.recordCancelled(SHIPMENT_ID, OCCURRED_AT);
        assertThat(state.hasCancellation()).isFalse();

        state.recordCreated(SHIPMENT_ID);
        assertThat(state.hasCancellation()).isTrue();
        assertThat(state.hasHandover()).isFalse();
    }

    @Test
    void doesNotTreatAnUnfinishedOrHandedOverShipmentAsCancelled() {
        ShipmentState state = new ShipmentState();
        state.recordCreated(SHIPMENT_ID);
        assertThat(state.hasCancellation()).isFalse();

        state.recordHandover(SHIPMENT_ID, OCCURRED_AT);
        assertThat(state.hasCancellation()).isFalse();
        assertThat(state.hasHandover()).isTrue();
    }

    @Test
    void acceptsAnIdempotentReplayOfTheSameCarrierHandover() {
        ShipmentState state = new ShipmentState();
        state.recordCreated(SHIPMENT_ID);

        state.recordHandover(SHIPMENT_ID, OCCURRED_AT);
        state.recordHandover(SHIPMENT_ID, OCCURRED_AT);

        assertThat(state.terminalOutcome().status()).isEqualTo(ShipmentTerminalStatus.HANDED_OVER);
    }

    @Test
    void rejectsMutuallyExclusiveCancellationAndHandoverFacts() {
        ShipmentState state = new ShipmentState();
        state.recordCreated(SHIPMENT_ID);
        state.recordCancelled(SHIPMENT_ID, OCCURRED_AT);
        ShipmentTerminalOutcome accepted = state.terminalOutcome();

        assertThatThrownBy(() -> state.recordHandover(SHIPMENT_ID, OCCURRED_AT.plusSeconds(1)))
                .isInstanceOf(ApplicationFailure.class)
                .hasMessageContaining("conflicting terminal facts");

        assertThat(state.shipmentIdOrNull()).isEqualTo(SHIPMENT_ID);
        assertThat(state.terminalOutcome()).isSameAs(accepted);
    }
}
