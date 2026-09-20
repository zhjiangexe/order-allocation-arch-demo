package com.flowzati.archone.ordering.application.event;

import com.flowzati.archone.ordering.domain.aggregate.Order;
import java.util.UUID;

public record OrderCancellationResolved(UUID requestId, UUID orderId, Order.CancellationStatus status) {}
