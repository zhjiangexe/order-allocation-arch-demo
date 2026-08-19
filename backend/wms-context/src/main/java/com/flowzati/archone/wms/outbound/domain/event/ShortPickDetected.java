package com.flowzati.archone.wms.outbound.domain.event;

import com.flowzati.archone.wms.shared.domain.WmsDomainEvent;
import java.time.Instant;
import java.util.UUID;

public record ShortPickDetected(
        UUID shipmentId,
        UUID pickTaskId,
        UUID moveId,
        String skuCode,
        UUID sourceLocationId,
        int expectedQuantity,
        int actualQuantity,
        Instant occurredAt)
        implements WmsDomainEvent {}
