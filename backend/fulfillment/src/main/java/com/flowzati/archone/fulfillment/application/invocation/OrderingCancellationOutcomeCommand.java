package com.flowzati.archone.fulfillment.application.invocation;

import com.flowzati.archone.fulfillment.application.enums.OrderingCancellationOutcome;
import java.util.UUID;

/** Transport-independent Ordering result for one cancellation request. */
public record OrderingCancellationOutcomeCommand(UUID requestId, UUID orderId, OrderingCancellationOutcome outcome) {}
