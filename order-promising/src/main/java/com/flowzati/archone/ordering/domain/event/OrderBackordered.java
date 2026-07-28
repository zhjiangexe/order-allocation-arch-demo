package com.flowzati.archone.ordering.domain.event;

import com.flowzati.archone.common.ddd.DomainEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 缺貨。採 ship-complete，整張單一起進缺貨，因此帶的是整張單的行。 */
public record OrderBackordered(
    UUID orderId,
    UUID ownerId,
    List<LineSnapshot> lines,
    Instant backorderedSince
) implements DomainEvent {
}
