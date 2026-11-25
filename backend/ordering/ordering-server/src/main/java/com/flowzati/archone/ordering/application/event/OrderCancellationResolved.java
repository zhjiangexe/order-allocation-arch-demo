package com.flowzati.archone.ordering.application.event;

import com.flowzati.archone.ordering.domain.enums.OrderCancellationOutcome;
import java.util.UUID;

public record OrderCancellationResolved(UUID requestId, UUID orderId, OrderCancellationOutcome status) {}
