package com.flowzati.archone.wms.outbound.application.command;

import java.time.Instant;
import java.util.UUID;

public record StageShipmentCommand(UUID shipmentId, Instant stagedAt) {
}
