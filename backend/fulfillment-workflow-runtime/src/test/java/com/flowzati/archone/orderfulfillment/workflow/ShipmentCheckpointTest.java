package com.flowzati.archone.orderfulfillment.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowzati.archone.orderfulfillment.contract.workflow.ShipmentTerminalStatus;
import io.temporal.failure.ApplicationFailure;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ShipmentCheckpointTest {

    private static final UUID SHIPMENT_ID = UUID.randomUUID();
    private static final Instant OCCURRED_AT = Instant.parse("2026-08-24T10:00:00Z");

    @Test
    void correlatesATerminalFactReportedAfterShipmentCreation() {
        ShipmentCheckpoint checkpoint = new ShipmentCheckpoint();
        checkpoint.recordCreated(SHIPMENT_ID);

        checkpoint.recordHandover(SHIPMENT_ID, OCCURRED_AT);

        assertThat(checkpoint.requireShipmentId()).isEqualTo(SHIPMENT_ID);
        assertThat(checkpoint.requireTerminal().status()).isEqualTo(ShipmentTerminalStatus.HANDED_OVER);
    }

    @Test
    void correlatesATerminalFactReportedBeforeTheActivityResponse() {
        ShipmentCheckpoint checkpoint = new ShipmentCheckpoint();
        checkpoint.recordHandover(SHIPMENT_ID, OCCURRED_AT);

        assertThat(checkpoint.hasTerminal()).isFalse();
        assertThat(checkpoint.terminalStatusOrNull()).isNull();

        checkpoint.recordCreated(SHIPMENT_ID);

        assertThat(checkpoint.hasTerminal()).isTrue();
        assertThat(checkpoint.requireTerminal().status()).isEqualTo(ShipmentTerminalStatus.HANDED_OVER);
    }

    @Test
    void failsInsteadOfWaitingForeverWhenAnEarlyTerminalBelongsToAnotherShipment() {
        ShipmentCheckpoint checkpoint = new ShipmentCheckpoint();
        checkpoint.recordHandover(UUID.randomUUID(), OCCURRED_AT);

        assertThatThrownBy(() -> checkpoint.recordCreated(SHIPMENT_ID))
                .isInstanceOf(ApplicationFailure.class)
                .hasMessageContaining("different Shipment before creation completed");
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

        assertThat(checkpoint.requireTerminal().status()).isEqualTo(ShipmentTerminalStatus.CANCELLED);
    }

    @Test
    void acceptsAnIdempotentReplayOfTheSameCarrierHandover() {
        ShipmentCheckpoint checkpoint = new ShipmentCheckpoint();
        checkpoint.recordCreated(SHIPMENT_ID);

        checkpoint.recordHandover(SHIPMENT_ID, OCCURRED_AT);
        checkpoint.recordHandover(SHIPMENT_ID, OCCURRED_AT);

        assertThat(checkpoint.requireTerminal().status()).isEqualTo(ShipmentTerminalStatus.HANDED_OVER);
    }

    @Test
    void rejectsMutuallyExclusiveCancellationAndHandoverFacts() {
        ShipmentCheckpoint checkpoint = new ShipmentCheckpoint();
        checkpoint.recordCreated(SHIPMENT_ID);
        checkpoint.recordCancelled(SHIPMENT_ID, OCCURRED_AT);
        checkpoint.recordHandover(SHIPMENT_ID, OCCURRED_AT.plusSeconds(1));

        assertThatThrownBy(checkpoint::requireTerminal)
                .isInstanceOf(ApplicationFailure.class)
                .hasMessageContaining("conflicting cancellation and handover facts");
    }
}
