package com.flowzati.archone.wms.receiving.application.invocation;

import java.time.Instant;
import java.util.UUID;

public record RecordInspectionCommand(UUID inboundOperationId, boolean accepted, String reason, Instant inspectedAt) {}
