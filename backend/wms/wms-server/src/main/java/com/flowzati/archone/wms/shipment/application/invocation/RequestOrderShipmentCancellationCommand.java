package com.flowzati.archone.wms.shipment.application.invocation;

import java.time.Instant;
import java.util.UUID;

public record RequestOrderShipmentCancellationCommand(
        UUID requestId, UUID orderId, Instant requestedAt, String reason) {}
