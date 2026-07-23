package com.flowzati.archone.allocation.domain.service;

import java.time.Instant;

public record AllocationRequest(
    Long stockPoolId,
    String sku,
    int availableToPromise,
    Instant decisionAt
) {
}
