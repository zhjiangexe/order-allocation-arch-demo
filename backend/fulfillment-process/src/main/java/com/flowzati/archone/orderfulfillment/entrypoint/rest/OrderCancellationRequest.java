package com.flowzati.archone.orderfulfillment.entrypoint.rest;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

/** HTTP retry 必須重送完全相同的 requestId、requestedAt 與 reason。 */
public record OrderCancellationRequest(
        @NotNull UUID requestId,
        @NotNull Instant requestedAt,
        @NotBlank String reason) {}
