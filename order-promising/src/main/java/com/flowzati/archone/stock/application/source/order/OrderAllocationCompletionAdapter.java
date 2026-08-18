package com.flowzati.archone.stock.application.source.order;

import com.flowzati.archone.stock.domain.event.AllocationCommitted;
import com.flowzati.archone.stock.domain.event.OrderAllocationCompleted;
import com.flowzati.archone.stock.domain.model.AllocationSourceType;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** 把 generic {@code AllocationCommitted} 轉成既有 order/fulfillment v1 source fact。 */
@Component
public class OrderAllocationCompletionAdapter {

  public OrderAllocationCompleted translate(AllocationCommitted fact) {
    if (fact.source().sourceType() != AllocationSourceType.ORDER) {
      throw new IllegalArgumentException("Only ORDER allocation completion can use the order adapter");
    }
    UUID orderId;
    try {
      orderId = UUID.fromString(fact.source().sourceId());
    } catch (IllegalArgumentException invalidCanonicalId) {
      throw new IllegalStateException("Canonical ORDER source ID must be a UUID", invalidCanonicalId);
    }
    Set<UUID> pickingIds = fact.moves().stream()
        .map(AllocationCommitted.CommittedAllocationMove::pickingId)
        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    if (pickingIds.size() != 1 || pickingIds.contains(null)) {
      throw new IllegalStateException("Order fulfillment v1 requires exactly one picking allocation ID");
    }
    return new OrderAllocationCompleted(
        pickingIds.iterator().next(),
        orderId,
        fact.ownerId(),
        fact.facilityId(),
        fact.moves().stream().map(move -> new OrderAllocationCompleted.AllocationLine(
            UUID.fromString(move.sourceLineId()),
            move.moveId(),
            move.skuCode(),
            move.sourceLocationId(),
            move.quantity())).toList(),
        fact.requiredBy(),
        fact.releasePriority(),
        fact.occurredAt());
  }
}
