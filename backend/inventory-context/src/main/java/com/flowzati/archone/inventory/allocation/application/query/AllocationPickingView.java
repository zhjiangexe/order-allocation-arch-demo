package com.flowzati.archone.inventory.allocation.application.query;

import java.time.Instant;
import java.util.UUID;

/** Demand execution 所屬的 StockPicking 摘要。 */
public record AllocationPickingView(
        UUID pickingId,
        UUID orderId,
        String direction,
        String state,
        UUID fromLocationId,
        UUID toLocationId,
        Instant dispatchBy,
        Integer releasePriority) {}
