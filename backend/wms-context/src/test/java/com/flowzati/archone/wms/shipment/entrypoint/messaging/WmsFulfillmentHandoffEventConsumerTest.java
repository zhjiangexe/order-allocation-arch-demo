package com.flowzati.archone.wms.shipment.entrypoint.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowzati.archone.contracts.promising.v1.OrderAllocationCommittedIntegrationEvent.AllocationLine;
import com.flowzati.archone.contracts.promising.v1.OrderAllocationCommittedIntegrationEvent.AllocationSlice;
import com.flowzati.archone.contracts.promising.v2.OrderAllocationCommittedIntegrationEvent;
import com.flowzati.archone.wms.shipment.application.invocation.CreateShipmentCommand;
import com.flowzati.archone.wms.shipment.application.service.LegacyAllocationPickingResolver;
import com.flowzati.archone.wms.shipment.application.usecase.CreateShipmentUsecase;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class WmsFulfillmentHandoffEventConsumerTest {

    @Test
    void mapsTheStandaloneHandoffSnapshotToThePureWmsUsecase() {
        CreateShipmentUsecase usecase = mock(CreateShipmentUsecase.class);
        var consumer = new WmsFulfillmentHandoffEventConsumer(usecase, mock(LegacyAllocationPickingResolver.class));
        UUID stockOperationId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID facilityId = UUID.randomUUID();
        UUID orderLineId = UUID.randomUUID();
        UUID moveId = UUID.randomUUID();
        UUID sourceLocationId = UUID.randomUUID();
        Instant assignedAt = Instant.parse("2026-08-11T01:00:00Z");
        Instant dispatchBy = Instant.parse("2026-08-11T08:00:00Z");
        var event = new OrderAllocationCommittedIntegrationEvent(
                UUID.randomUUID(),
                stockOperationId,
                orderId,
                ownerId,
                facilityId,
                UUID.randomUUID(),
                sourceLocationId,
                UUID.randomUUID(),
                List.of(new OrderAllocationCommittedIntegrationEvent.AssignedMove(
                        orderLineId,
                        moveId,
                        "SKU-1",
                        3,
                        List.of(new OrderAllocationCommittedIntegrationEvent.BatchPick(UUID.randomUUID(), 3)))),
                dispatchBy,
                80,
                assignedAt);

        consumer.onPickingAssigned(event);

        ArgumentCaptor<CreateShipmentCommand> command = ArgumentCaptor.forClass(CreateShipmentCommand.class);
        verify(usecase).handle(command.capture());
        assertThat(command.getValue().shipmentId()).isNotNull();
        assertThat(command.getValue().stockOperationId()).isEqualTo(stockOperationId);
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
        assertThat(command.getValue().createdAt()).isEqualTo(assignedAt);
    }

    @Test
    void mapsCanonicalStockOperationHandoffToTheSameWmsCommand() {
        CreateShipmentUsecase usecase = mock(CreateShipmentUsecase.class);
        var consumer = new WmsFulfillmentHandoffEventConsumer(usecase, mock(LegacyAllocationPickingResolver.class));
        UUID stockOperationId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID sourceLocationId = UUID.randomUUID();
        Instant assignedAt = Instant.parse("2026-08-11T01:00:00Z");
        var event = new com.flowzati.archone.contracts.promising.v3.OrderAllocationCommittedIntegrationEvent(
                UUID.randomUUID(),
                stockOperationId,
                orderId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                sourceLocationId,
                UUID.randomUUID(),
                List.of(
                        new com.flowzati.archone.contracts.promising.v3.OrderAllocationCommittedIntegrationEvent
                                .AssignedMove(
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                "SKU-1",
                                3,
                                List.of(new com.flowzati.archone.contracts.promising.v3
                                        .OrderAllocationCommittedIntegrationEvent.BatchPick(UUID.randomUUID(), 3)))),
                assignedAt.plusSeconds(3600),
                80,
                assignedAt);

        consumer.onStockOperationAssigned(event);

        ArgumentCaptor<CreateShipmentCommand> command = ArgumentCaptor.forClass(CreateShipmentCommand.class);
        verify(usecase).handle(command.capture());
        assertThat(command.getValue().stockOperationId()).isEqualTo(stockOperationId);
        assertThat(command.getValue().orderId()).isEqualTo(orderId);
        assertThat(command.getValue().lines())
                .singleElement()
                .satisfies(line -> assertThat(line.sourceLocationId()).isEqualTo(sourceLocationId));
    }

    @Test
    void resolvesRetainedLegacyAllocationIdentityFromCanonicalMoves() {
        CreateShipmentUsecase usecase = mock(CreateShipmentUsecase.class);
        LegacyAllocationPickingResolver resolver = mock(LegacyAllocationPickingResolver.class);
        var consumer = new WmsFulfillmentHandoffEventConsumer(usecase, resolver);
        UUID legacyAllocationId = UUID.randomUUID();
        UUID stockOperationId = UUID.randomUUID();
        UUID moveId = UUID.randomUUID();
        UUID orderLineId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID facilityId = UUID.randomUUID();
        UUID sourceLocationId = UUID.randomUUID();
        Instant committedAt = Instant.parse("2026-08-11T01:00:00Z");
        when(resolver.resolve(legacyAllocationId, List.of(moveId))).thenReturn(stockOperationId);
        var event = new com.flowzati.archone.contracts.promising.v1.OrderAllocationCommittedIntegrationEvent(
                UUID.randomUUID(),
                legacyAllocationId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                ownerId,
                facilityId,
                List.of(new AllocationLine(
                        orderLineId,
                        UUID.randomUUID(),
                        moveId,
                        "SKU-1",
                        sourceLocationId,
                        3,
                        List.of(new AllocationSlice(UUID.randomUUID(), UUID.randomUUID(), 3)))),
                committedAt.plusSeconds(3600),
                80,
                committedAt);

        consumer.onLegacyAllocationCommitted(event);

        ArgumentCaptor<CreateShipmentCommand> command = ArgumentCaptor.forClass(CreateShipmentCommand.class);
        verify(usecase).handle(command.capture());
        verify(resolver).resolve(legacyAllocationId, List.of(moveId));
        assertThat(command.getValue().stockOperationId()).isEqualTo(stockOperationId);
    }
}
