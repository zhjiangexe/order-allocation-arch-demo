package com.flowzati.archone.orderfulfillment.application.event;

import java.time.Instant;
import java.util.UUID;

public record OrderingCancellationRequested(UUID requestId, UUID orderId, Instant requestedAt, String reason) {}
