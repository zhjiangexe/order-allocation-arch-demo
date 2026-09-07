package com.flowzati.archone.orchestration.runtime.workflow.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowzati.archone.orchestration.contract.workflow.order.result.ShipmentTerminalStatus;
import io.temporal.failure.ApplicationFailure;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ShipmentCheckpointTest {

    private static final UUID SHIPMENT_ID = UUID.randomUUID();
    private static final Instant OCCURRED_AT = Instant.parse("2026-08-24T10:00:00Z");

    @Test
    void acceptsAnIdempotentReplayOfTheCreatedShipment() {
        ShipmentCheckpoint checkpoint = new ShipmentCheckpoint();
        checkpoint.recordCreated(SHIPMENT_ID);
        checkpoint.recordHandover(SHIPMENT_ID, OCCURRED_AT);

        ShipmentTerminalOutcome accepted = checkpoint.terminalOutcome();
        checkpoint.recordCreated(SHIPMENT_ID);

        assertThat(checkpoint.shipmentIdOrNull()).isEqualTo(SHIPMENT_ID);
        assertThat(checkpoint.terminalOutcome()).isSameAs(accepted);
    }

    @Test
    void correlatesATerminalFactReportedAfterShipmentCreation() {
        ShipmentCheckpoint checkpoint = new ShipmentCheckpoint();
        checkpoint.recordCreated(SHIPMENT_ID);

        checkpoint.recordHandover(SHIPMENT_ID, OCCURRED_AT);

        assertThat(checkpoint.shipmentIdOrNull()).isEqualTo(SHIPMENT_ID);
        assertThat(checkpoint.terminalOutcome().status()).isEqualTo(ShipmentTerminalStatus.HANDED_OVER);
    }

    @Test
    void correlatesATerminalFactReportedBeforeTheActivityResponse() {
        ShipmentCheckpoint checkpoint = new ShipmentCheckpoint();
        checkpoint.recordHandover(SHIPMENT_ID, OCCURRED_AT);

        assertThat(checkpoint.hasTerminal()).isFalse();
        assertThat(checkpoint.terminalStatusOrNull()).isNull();

        checkpoint.recordCreated(SHIPMENT_ID);

        assertThat(checkpoint.hasTerminal()).isTrue();
        assertThat(checkpoint.terminalOutcome().status()).isEqualTo(ShipmentTerminalStatus.HANDED_OVER);
    }

    @Test
    void failsInsteadOfWaitingForeverWhenAnEarlyTerminalBelongsToAnotherShipment() {
        ShipmentCheckpoint checkpoint = new ShipmentCheckpoint();
        UUID reportedShipmentId = UUID.randomUUID();
        checkpoint.recordHandover(reportedShipmentId, OCCURRED_AT);
        ShipmentTerminalOutcome accepted = checkpoint.terminalOutcome();

        assertThatThrownBy(() -> checkpoint.recordCreated(SHIPMENT_ID))
                .isInstanceOf(ApplicationFailure.class)
                .hasMessageContaining("different Shipment before creation completed");

        assertThat(checkpoint.shipmentIdOrNull()).isNull();
        assertThat(checkpoint.terminalOutcome()).isSameAs(accepted);
        assertThat(checkpoint.hasTerminal()).isFalse();
    }

    @Test
    void ignoresAnotherShipmentAfterTheCreatedIdentityIsKnown() {
        ShipmentCheckpoint checkpoint = new ShipmentCheckpoint();
        checkpoint.recordCreated(SHIPMENT_ID);

        checkpoint.recordHandover(UUID.randomUUID(), OCCURRED_AT);

        assertThat(checkpoint.hasTerminal()).isFalse();
    }

    @Test
    void acceptsAnIdempotentReplayOfTheSameCancellation() {
        ShipmentCheckpoint checkpoint = new ShipmentCheckpoint();
        checkpoint.recordCreated(SHIPMENT_ID);

        checkpoint.recordCancelled(SHIPMENT_ID, OCCURRED_AT);
        checkpoint.recordCancelled(SHIPMENT_ID, OCCURRED_AT);

        assertThat(checkpoint.terminalOutcome().status()).isEqualTo(ShipmentTerminalStatus.CANCELLED);
    }

    @Test
    void recognizesCancellationOnlyAfterTheShipmentIdentityIsConfirmed() {
        ShipmentCheckpoint checkpoint = new ShipmentCheckpoint();
        assertThat(checkpoint.hasCancellation()).isFalse();

        checkpoint.recordCancelled(SHIPMENT_ID, OCCURRED_AT);
        assertThat(checkpoint.hasCancellation()).isFalse();

        checkpoint.recordCreated(SHIPMENT_ID);
        assertThat(checkpoint.hasCancellation()).isTrue();
        assertThat(checkpoint.hasHandover()).isFalse();
    }

    @Test
    void doesNotTreatAnUnfinishedOrHandedOverShipmentAsCancelled() {
        ShipmentCheckpoint checkpoint = new ShipmentCheckpoint();
        checkpoint.recordCreated(SHIPMENT_ID);
        assertThat(checkpoint.hasCancellation()).isFalse();

        checkpoint.recordHandover(SHIPMENT_ID, OCCURRED_AT);
        assertThat(checkpoint.hasCancellation()).isFalse();
        assertThat(checkpoint.hasHandover()).isTrue();
    }

    @Test
    void acceptsAnIdempotentReplayOfTheSameCarrierHandover() {
        ShipmentCheckpoint checkpoint = new ShipmentCheckpoint();
        checkpoint.recordCreated(SHIPMENT_ID);

        checkpoint.recordHandover(SHIPMENT_ID, OCCURRED_AT);
        checkpoint.recordHandover(SHIPMENT_ID, OCCURRED_AT);

        assertThat(checkpoint.terminalOutcome().status()).isEqualTo(ShipmentTerminalStatus.HANDED_OVER);
    }

    @Test
    void rejectsMutuallyExclusiveCancellationAndHandoverFacts() {
        ShipmentCheckpoint checkpoint = new ShipmentCheckpoint();
        checkpoint.recordCreated(SHIPMENT_ID);
        checkpoint.recordCancelled(SHIPMENT_ID, OCCURRED_AT);
        ShipmentTerminalOutcome accepted = checkpoint.terminalOutcome();

        assertThatThrownBy(() -> checkpoint.recordHandover(SHIPMENT_ID, OCCURRED_AT.plusSeconds(1)))
                .isInstanceOf(ApplicationFailure.class)
                .hasMessageContaining("conflicting terminal facts");

        assertThat(checkpoint.shipmentIdOrNull()).isEqualTo(SHIPMENT_ID);
        assertThat(checkpoint.terminalOutcome()).isSameAs(accepted);
    }
}
