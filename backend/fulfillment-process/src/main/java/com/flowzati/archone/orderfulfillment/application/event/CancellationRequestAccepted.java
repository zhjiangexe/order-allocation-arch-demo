package com.flowzati.archone.orderfulfillment.application.event;

import java.time.Instant;
import java.util.UUID;

/** The Events-mode entrypoint accepted a cancellation request for asynchronous processing. */
public record CancellationRequestAccepted(UUID requestId, UUID orderId, Instant requestedAt, String reason) {}
