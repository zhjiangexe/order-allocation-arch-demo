package com.flowzati.archone.ordering.domain.event;

import com.flowzati.archone.common.ddd.DomainEvent;
import java.time.Instant;
import java.util.UUID;

public record OrderAllocated(
    UUID orderId,
    UUID ownerId,
    Instant allocatedAt
) implements DomainEvent {
}
