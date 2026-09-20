package com.flowzati.archone.wms.dispatch.application.invocation;

import java.time.Instant;
import java.util.UUID;

public record PackShipmentCommand(UUID shipmentId, Instant packedAt) {}
