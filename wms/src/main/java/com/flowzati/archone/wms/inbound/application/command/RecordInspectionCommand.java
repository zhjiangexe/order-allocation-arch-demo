package com.flowzati.archone.wms.inbound.application.command;

import java.time.Instant;
import java.util.UUID;

public record RecordInspectionCommand(
    UUID inboundOperationId,
    boolean accepted,
    String reason,
    Instant inspectedAt
) {
}
