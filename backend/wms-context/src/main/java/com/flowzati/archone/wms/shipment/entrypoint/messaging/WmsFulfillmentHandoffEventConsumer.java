package com.flowzati.archone.wms.shipment.entrypoint.messaging;

import com.flowzati.archone.contracts.promising.v1.AllocationEventDestinations;
import com.flowzati.archone.contracts.promising.v1.OrderAllocationCommittedIntegrationEvent;
import com.flowzati.archone.foundation.identity.IdGenerator;
import com.flowzati.archone.messaging.autoconfigure.ConditionalOnIntegrationEventConsumption;
import com.flowzati.archone.messaging.events.IntegrationEventDispatcher;
import com.flowzati.archone.messaging.events.IntegrationEventDispatcherFactory;
import com.flowzati.archone.messaging.events.IntegrationEventHandlers;
import com.flowzati.archone.messaging.events.IntegrationEventHandlersBuilder;
import com.flowzati.archone.wms.shipment.application.invocation.CreateShipmentCommand;
import com.flowzati.archone.wms.shipment.application.usecase.CreateShipmentUsecase;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Tram-style fulfillment handoff consumer: Inbox transaction surrounds this complete handler. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnIntegrationEventConsumption
@ConditionalOnProperty(name = "archone.fulfillment.orchestration-mode", havingValue = "events", matchIfMissing = true)
public class WmsFulfillmentHandoffEventConsumer {

    private final CreateShipmentUsecase createShipmentUsecase;

    public WmsFulfillmentHandoffEventConsumer(CreateShipmentUsecase createShipmentUsecase) {
        this.createShipmentUsecase = createShipmentUsecase;
    }

    @Bean
    IntegrationEventDispatcher wmsFulfillmentHandoffIntegrationEventDispatcher(
            IntegrationEventDispatcherFactory factory) {
        IntegrationEventHandlers handlers = IntegrationEventHandlersBuilder.forDestination(
                        AllocationEventDestinations.ALLOCATION_EVENTS)
                .onEvent(
                        OrderAllocationCommittedIntegrationEvent.class,
                        envelope -> onStockOperationAssigned(envelope.event()))
                .build();
        return factory.make(WmsEventSubscriptions.FULFILLMENT_HANDOFF, handlers);
    }

    void onStockOperationAssigned(OrderAllocationCommittedIntegrationEvent event) {
        accept(new StockOperationAssignment(
                event.getStockOperationId(),
                event.getOrderId(),
                event.getOwnerId(),
                event.getFacilityId(),
                event.getMoves().stream()
                        .map(line -> new AssignedMovement(
                                line.orderLineId(),
                                line.moveId(),
                                line.skuCode(),
                                event.getSourceLocationId(),
                                line.quantity()))
                        .toList(),
                event.getDispatchBy(),
                event.getReleasePriority(),
                event.getAssignedAt()));
    }

    private void accept(StockOperationAssignment assignment) {
        createShipmentUsecase.handle(new CreateShipmentCommand(
                IdGenerator.nextId(),
                assignment.stockOperationId(),
                assignment.orderId(),
                assignment.ownerId(),
                assignment.facilityId(),
                assignment.moves().stream()
                        .map(line -> new CreateShipmentCommand.MovementLine(
                                line.orderLineId(),
                                line.moveId(),
                                line.skuCode(),
                                line.sourceLocationId(),
                                line.quantity()))
                        .toList(),
                assignment.dispatchBy(),
                assignment.releasePriority(),
                assignment.assignedAt()));
    }

    private record StockOperationAssignment(
            UUID stockOperationId,
            UUID orderId,
            UUID ownerId,
            UUID facilityId,
            List<AssignedMovement> moves,
            Instant dispatchBy,
            int releasePriority,
            Instant assignedAt) {}

    private record AssignedMovement(
            UUID orderLineId, UUID moveId, String skuCode, UUID sourceLocationId, int quantity) {}
}
