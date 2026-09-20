package com.flowzati.archone.wms.receiving.application.invocation;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record ConfirmPutawayCommand(UUID inboundOperationId, List<ActualLine> lines, Instant completedAt) {

    public ConfirmPutawayCommand {
        if (lines == null) {
            throw new IllegalArgumentException("Putaway lines are required");
        }
        lines = List.copyOf(lines);
    }

    public record ActualLine(String skuCode, UUID locationId, LocalDate inDate, LocalDate expiryDate, int quantity) {}
}
