package com.flowzati.archone.ordering.api.query;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record OrderQueryView(
        UUID orderId,
        UUID ownerId,
        String externalOrderNo,
        UUID facilityId,
        String shipToZone,
        String shipToAddress,
        LocalDate promisedDeliveryDate,
        Instant dispatchBy,
        int releasePriority,
        String status,
        Instant receivedAt,
        Instant placedAt,
        Instant allocatedAt,
        Instant cancelledAt,
        UUID cancellationRequestId,
        String cancellationReason,
        Instant fulfilledAt,
        UUID fulfilledByShipmentId,
        List<OrderLineQueryView> lines) {

    public OrderQueryView {
        lines = List.copyOf(lines);
    }
}
