package com.flowzati.archone.wms.inbound.application.command;

import java.time.Instant;
import java.util.UUID;

public record ConfirmArrivalCommand(UUID inboundOperationId, Instant arrivedAt) {
}
