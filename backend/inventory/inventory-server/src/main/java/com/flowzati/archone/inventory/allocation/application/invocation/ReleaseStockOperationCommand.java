package com.flowzati.archone.inventory.allocation.application.invocation;

import java.time.Instant;
import java.util.UUID;

/** 釋放一張作業目前持有的全部預留。 */
public record ReleaseStockOperationCommand(UUID stockOperationId, Instant occurredAt) {

    public ReleaseStockOperationCommand {
        if (stockOperationId == null || occurredAt == null) {
            throw new IllegalArgumentException("Stock operation identity and release time are required");
        }
    }
}
