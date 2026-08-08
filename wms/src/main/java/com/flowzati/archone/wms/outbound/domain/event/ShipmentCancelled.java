package com.flowzati.archone.wms.outbound.domain.event;

import com.flowzati.archone.wms.shared.domain.WmsDomainEvent;
import java.time.Instant;
import java.util.UUID;

public record ShipmentCancelled(UUID shipmentId, UUID orderId, String requestId, Instant occurredAt)
    implements WmsDomainEvent {
}
