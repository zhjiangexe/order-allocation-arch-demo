package com.flowzati.archone.wms.outbound.domain.event;

import com.flowzati.archone.wms.shared.domain.WmsDomainEvent;
import java.time.Instant;
import java.util.UUID;

public record ShipmentCreated(UUID shipmentId, UUID orderId, UUID allocationId, Instant occurredAt)
    implements WmsDomainEvent {
}
