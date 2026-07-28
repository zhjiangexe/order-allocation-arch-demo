package com.flowzati.archone.ordering.domain.event;

import com.flowzati.archone.common.ddd.DomainEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 取消是整單行為，因此帶整張單的行——下游要釋放的預留也是整單的。 */
public record OrderCancelled(
    UUID orderId,
    UUID ownerId,
    List<LineSnapshot> lines,
    Instant cancelledAt
) implements DomainEvent {
}
