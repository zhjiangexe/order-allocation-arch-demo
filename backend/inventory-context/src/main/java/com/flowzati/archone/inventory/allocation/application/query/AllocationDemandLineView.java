package com.flowzati.archone.inventory.allocation.application.query;

import java.util.UUID;

/** Allocation-owned immutable demand line。 */
public record AllocationDemandLineView(
        UUID lineId, String sourceLineId, int lineSequence, String skuCode, int quantity) {}
