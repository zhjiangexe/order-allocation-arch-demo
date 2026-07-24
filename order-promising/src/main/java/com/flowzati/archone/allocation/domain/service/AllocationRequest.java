package com.flowzati.archone.allocation.domain.service;

import java.time.Instant;
import java.util.UUID;

public record AllocationRequest(
    UUID stockPoolId,
    String sku,
    int availableToPromise,
    Instant decisionAt
) {
}
