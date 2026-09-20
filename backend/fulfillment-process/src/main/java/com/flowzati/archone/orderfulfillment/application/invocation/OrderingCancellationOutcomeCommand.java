package com.flowzati.archone.orderfulfillment.application.invocation;

import java.util.UUID;

/** Transport-independent Ordering result for one cancellation request. */
public record OrderingCancellationOutcomeCommand(UUID requestId, UUID orderId, Outcome outcome) {
    public enum Outcome {
        SUCCEEDED,
        REJECTED
    }
}
