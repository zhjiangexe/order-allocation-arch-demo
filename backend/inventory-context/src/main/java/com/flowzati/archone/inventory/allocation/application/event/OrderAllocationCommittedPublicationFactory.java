package com.flowzati.archone.inventory.allocation.application.event;

import com.flowzati.archone.contracts.ordering.v1.OrderingAggregateTypes;
import com.flowzati.archone.contracts.promising.v1.AllocationChannels;
import com.flowzati.archone.contracts.promising.v1.OrderAllocationCommittedIntegrationEvent;
import com.flowzati.archone.foundation.identity.IdGenerator;
import com.flowzati.archone.inventory.allocation.application.result.AllocationCommitResult;
import com.flowzati.archone.inventory.allocation.domain.type.AllocationSourceType;
import com.flowzati.archone.messaging.events.AggregateReference;
import com.flowzati.archone.messaging.events.IntegrationEventPublication;
import com.flowzati.archone.messaging.events.PublicationTarget;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Creates the single canonical publication for a committed order allocation.
 */
public final class OrderAllocationCommittedPublicationFactory {

    private OrderAllocationCommittedPublicationFactory() {}

    public static IntegrationEventPublication create(AllocationCommitResult result) {
        if (result.source().sourceType() != AllocationSourceType.ORDER) {
            throw new IllegalStateException("Only ORDER allocation completion can use the order publication");
        }
        UUID orderId = canonicalOrderId(result);
        UUID allocationId = fulfillmentAllocationId(result);
        List<OrderAllocationCommittedIntegrationEvent.AllocationLine> lines = result.moves().stream()
                .map(move -> new OrderAllocationCommittedIntegrationEvent.AllocationLine(
                        UUID.fromString(move.sourceLineId()),
                        move.moveId(),
                        move.skuCode(),
                        move.sourceLocationId(),
                        move.quantity()))
                .toList();
        OrderAllocationCommittedIntegrationEvent event = new OrderAllocationCommittedIntegrationEvent(
                IdGenerator.nextId(),
                allocationId,
                orderId,
                result.ownerId(),
                result.facilityId(),
                lines,
                result.requiredBy(),
                result.releasePriority(),
                result.occurredAt());
        return new IntegrationEventPublication(
                event,
                new AggregateReference(OrderingAggregateTypes.ORDER, orderId.toString()),
                new PublicationTarget(AllocationChannels.ALLOCATION_EVENTS, orderId.toString()),
                result.occurredAt());
    }

    private static UUID canonicalOrderId(AllocationCommitResult result) {
        try {
            return UUID.fromString(result.source().sourceId());
        } catch (IllegalArgumentException invalidCanonicalId) {
            throw new IllegalStateException("Canonical ORDER source ID must be a UUID", invalidCanonicalId);
        }
    }

    private static UUID fulfillmentAllocationId(AllocationCommitResult result) {
        Set<UUID> pickingIds = result.moves().stream()
                .map(AllocationCommitResult.CommittedAllocationMove::pickingId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (pickingIds.size() != 1 || pickingIds.contains(null)) {
            throw new IllegalStateException("Order fulfillment v1 requires exactly one picking allocation ID");
        }
        return pickingIds.iterator().next();
    }
}
