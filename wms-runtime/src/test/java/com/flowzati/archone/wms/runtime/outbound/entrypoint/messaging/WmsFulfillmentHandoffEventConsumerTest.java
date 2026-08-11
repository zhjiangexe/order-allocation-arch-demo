package com.flowzati.archone.wms.runtime.outbound.entrypoint.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.flowzati.archone.contracts.fulfillment.v1.AllocationCommittedForFulfillmentIntegrationEvent;
import com.flowzati.archone.wms.outbound.application.command.CreateShipmentCommand;
import com.flowzati.archone.wms.outbound.application.usecase.CreateShipmentUsecase;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class WmsFulfillmentHandoffEventConsumerTest {

  @Test
  void mapsTheStandaloneHandoffSnapshotToThePureWmsUsecase() {
    CreateShipmentUsecase usecase = mock(CreateShipmentUsecase.class);
    UUID shipmentId = UUID.randomUUID();
    var consumer = new WmsFulfillmentHandoffEventConsumer(usecase, () -> shipmentId);
    UUID allocationId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    UUID ownerId = UUID.randomUUID();
    UUID facilityId = UUID.randomUUID();
    UUID orderLineId = UUID.randomUUID();
    UUID moveId = UUID.randomUUID();
    UUID sourceLocationId = UUID.randomUUID();
    Instant committedAt = Instant.parse("2026-08-11T01:00:00Z");
    Instant dispatchBy = Instant.parse("2026-08-11T08:00:00Z");
    var event = new AllocationCommittedForFulfillmentIntegrationEvent(
        UUID.randomUUID(),
        allocationId,
        orderId,
        ownerId,
        facilityId,
        List.of(new AllocationCommittedForFulfillmentIntegrationEvent.AllocationLine(
            orderLineId, moveId, "SKU-1", sourceLocationId, 3)),
        dispatchBy,
        80,
        committedAt);

    consumer.onAllocationCommitted(event);

    ArgumentCaptor<CreateShipmentCommand> command =
        ArgumentCaptor.forClass(CreateShipmentCommand.class);
    verify(usecase).handle(command.capture());
    assertThat(command.getValue().shipmentId()).isEqualTo(shipmentId);
    assertThat(command.getValue().allocationId()).isEqualTo(allocationId);
    assertThat(command.getValue().orderId()).isEqualTo(orderId);
    assertThat(command.getValue().ownerId()).isEqualTo(ownerId);
    assertThat(command.getValue().facilityId()).isEqualTo(facilityId);
    assertThat(command.getValue().lines()).singleElement().satisfies(line -> {
      assertThat(line.orderLineId()).isEqualTo(orderLineId);
      assertThat(line.moveId()).isEqualTo(moveId);
      assertThat(line.sourceLocationId()).isEqualTo(sourceLocationId);
      assertThat(line.quantity()).isEqualTo(3);
    });
    assertThat(command.getValue().dispatchBy()).isEqualTo(dispatchBy);
    assertThat(command.getValue().releasePriority()).isEqualTo(80);
    assertThat(command.getValue().createdAt()).isEqualTo(committedAt);
  }
}
