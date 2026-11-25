package com.flowzati.archone.fulfillment.application.invocation;

import com.flowzati.archone.fulfillment.application.enums.WmsCancellationOutcome;
import java.time.Instant;
import java.util.UUID;

/** Transport-independent WMS outcome correlated with the immutable cancellation request. */
public record WmsCancellationOutcomeCommand(
        UUID requestId, UUID orderId, Instant requestedAt, String reason, WmsCancellationOutcome outcome) {}
