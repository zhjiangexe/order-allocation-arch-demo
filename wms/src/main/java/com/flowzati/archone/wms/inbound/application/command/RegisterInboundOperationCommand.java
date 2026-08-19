package com.flowzati.archone.wms.inbound.application.command;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RegisterInboundOperationCommand(
        UUID inboundOperationId,
        UUID ownerId,
        UUID facilityId,
        String externalReference,
        List<ExpectedLine> lines,
        Instant registeredAt) {

    public RegisterInboundOperationCommand {
        if (lines == null) {
            throw new IllegalArgumentException("Inbound lines are required");
        }
        lines = List.copyOf(lines);
    }

    public record ExpectedLine(String skuCode, int quantity) {}
}
