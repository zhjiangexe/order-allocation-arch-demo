package com.flowzati.archone.inventory.reservation.infrastructure.messaging;

import com.flowzati.archone.contracts.ordering.v1.OrderingAggregateTypes;
import com.flowzati.archone.contracts.promising.v1.AllocationChannels;
import com.flowzati.archone.contracts.promising.v3.OrderAllocationCommittedIntegrationEvent;
import com.flowzati.archone.foundation.identity.IdGenerator;
import com.flowzati.archone.inventory.movement.domain.MovementSourceType;
import com.flowzati.archone.inventory.reservation.application.StockOperationAssignmentResult;
import com.flowzati.archone.messaging.events.AggregateReference;
import com.flowzati.archone.messaging.events.IntegrationEventPublication;
import com.flowzati.archone.messaging.events.PublicationTarget;
import java.util.UUID;

/** Builds the canonical stock-operation-centric order assignment fact. */
public final class OrderStockOperationAssignedPublicationFactory {

    private OrderStockOperationAssignedPublicationFactory() {}

    public static IntegrationEventPublication create(StockOperationAssignmentResult result) {
        if (result.source().sourceType() != MovementSourceType.ORDER) {
            throw new IllegalStateException("Only ORDER movement assignment can use the order publication");
        }
        // Order-specific contract translation 只在邊界發生，Inventory core 仍維持 source-neutral。
        UUID orderId = parseUuid(result.source().sourceId(), "Canonical ORDER source ID");
        var event = new OrderAllocationCommittedIntegrationEvent(
                IdGenerator.nextId(),
                result.stockOperationId(),
                orderId,
                result.ownerId(),
                result.facilityId(),
                result.stockOperationTypeId(),
                result.sourceLocationId(),
                result.destinationLocationId(),
                result.moves().stream()
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
                result.dispatchBy(),
                result.releasePriority(),
                result.assignedAt());
        return new IntegrationEventPublication(
                event,
                new AggregateReference(OrderingAggregateTypes.ORDER, orderId.toString()),
                new PublicationTarget(AllocationChannels.ALLOCATION_EVENTS, orderId.toString()),
                result.assignedAt());
    }

    private static UUID parseUuid(String value, String field) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException invalidId) {
            throw new IllegalStateException(field + " must be a UUID", invalidId);
        }
    }
}
