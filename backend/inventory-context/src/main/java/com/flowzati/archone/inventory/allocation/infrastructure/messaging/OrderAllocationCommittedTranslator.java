package com.flowzati.archone.inventory.allocation.infrastructure.messaging;

import com.flowzati.archone.contracts.ordering.v1.OrderingAggregateTypes;
import com.flowzati.archone.contracts.promising.v1.AllocationChannels;
import com.flowzati.archone.contracts.promising.v3.OrderAllocationCommittedIntegrationEvent;
import com.flowzati.archone.foundation.identity.IdGenerator;
import com.flowzati.archone.inventory.allocation.application.event.StockOperationAssigned;
import com.flowzati.archone.inventory.movement.domain.valueobject.MovementSourceType;
import com.flowzati.archone.messaging.events.AggregateReference;
import com.flowzati.archone.messaging.events.IntegrationEventPublication;
import com.flowzati.archone.messaging.events.PublicationTarget;
import java.util.UUID;

/** Translates a source-neutral Inventory assignment to the order allocation integration contract. */
public final class OrderAllocationCommittedTranslator {

    private OrderAllocationCommittedTranslator() {}

    public static IntegrationEventPublication translate(StockOperationAssigned event) {
        if (event.source().sourceType() != MovementSourceType.ORDER) {
            throw new IllegalStateException("Only ORDER movement assignment can use the order publication");
        }
        // Order-specific contract translation 只在邊界發生，Inventory core 仍維持 source-neutral。
        UUID orderId = parseUuid(event.source().sourceId(), "Canonical ORDER source ID");
        var integrationEvent = new OrderAllocationCommittedIntegrationEvent(
                IdGenerator.nextId(),
                event.stockOperationId(),
                orderId,
                event.ownerId(),
                event.facilityId(),
                event.stockOperationTypeId(),
                event.sourceLocationId(),
                event.destinationLocationId(),
                event.moves().stream()
                        .map(move -> new OrderAllocationCommittedIntegrationEvent.AssignedMove(
                                parseUuid(move.sourceLineId(), "Canonical ORDER source-line ID"),
                                move.moveId(),
                                move.skuCode(),
                                move.quantity(),
                                move.moveLines().stream()
                                        .map(line -> new OrderAllocationCommittedIntegrationEvent.BatchPick(
                                                line.stockQuantId(), line.quantity()))
                                        .toList()))
                        .toList(),
                event.dispatchBy(),
                event.releasePriority(),
                event.assignedAt());
        return new IntegrationEventPublication(
                integrationEvent,
                new AggregateReference(OrderingAggregateTypes.ORDER, orderId.toString()),
                new PublicationTarget(AllocationChannels.ALLOCATION_EVENTS, orderId.toString()),
                event.assignedAt());
    }

    private static UUID parseUuid(String value, String field) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException invalidId) {
            throw new IllegalStateException(field + " must be a UUID", invalidId);
        }
    }
}
