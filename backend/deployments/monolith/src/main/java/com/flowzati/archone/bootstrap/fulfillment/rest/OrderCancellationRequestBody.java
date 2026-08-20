package com.flowzati.archone.bootstrap.fulfillment.rest;

import java.time.Instant;
import java.util.UUID;

/** HTTP retry 必須重送完全相同的 requestId、requestedAt 與 reason。 */
public record OrderCancellationRequestBody(UUID requestId, Instant requestedAt, String reason) {}
