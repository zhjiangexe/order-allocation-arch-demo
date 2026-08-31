package com.flowzati.archone.wms.picking.application.invocation;

import java.time.Instant;
import java.util.UUID;

public record ConfirmPickCommand(UUID pickTaskId, int actualQuantity, Instant confirmedAt) {}
