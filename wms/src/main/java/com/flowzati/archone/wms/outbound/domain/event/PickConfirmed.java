package com.flowzati.archone.wms.outbound.domain.event;

import com.flowzati.archone.wms.shared.domain.WmsDomainEvent;
import java.time.Instant;
import java.util.UUID;

public record PickConfirmed(UUID shipmentId, UUID pickTaskId, UUID moveId, int quantity, Instant occurredAt)
        implements WmsDomainEvent {}
