package com.flowzati.archone.wms.outbound.domain.event;

import com.flowzati.archone.wms.shared.domain.WmsDomainEvent;
import java.time.Instant;
import java.util.UUID;

public record ShipmentCancellationRejected(
        UUID shipmentId, UUID orderId, String requestId, String reason, Instant occurredAt) implements WmsDomainEvent {}
