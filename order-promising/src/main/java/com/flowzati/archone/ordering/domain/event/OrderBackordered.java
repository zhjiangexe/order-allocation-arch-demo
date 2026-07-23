package com.flowzati.archone.ordering.domain.event;

import com.flowzati.archone.common.ddd.DomainEvent;
import java.time.Instant;
import java.util.UUID;

public record OrderBackordered(
    UUID orderId,
    String sku,
    int quantity,
    Instant backorderedSince
) implements DomainEvent {
}
