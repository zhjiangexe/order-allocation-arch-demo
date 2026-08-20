package com.flowzati.archone.inventory.allocation.application.query;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Allocation demand 對應的庫存搬運與批次預留。 */
public record AllocationMoveView(
        UUID moveId,
        UUID pickingId,
        UUID allocationDemandLineId,
        String sourceLineId,
        UUID orderLineId,
        String skuCode,
        int demandQuantity,
        String state,
        UUID fromLocationId,
        UUID toLocationId,
        Instant createdAt,
        Instant assignedAt,
        List<AllocationReservationView> reservations) {

    public AllocationMoveView {
        reservations = List.copyOf(reservations);
    }
}
