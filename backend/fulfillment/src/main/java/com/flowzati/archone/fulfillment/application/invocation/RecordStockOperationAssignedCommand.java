package com.flowzati.archone.fulfillment.application.invocation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 庫存已為訂單配好貨（stock operation 已 assigned）。 */
public record RecordStockOperationAssignedCommand(
        UUID stockOperationId,
        UUID orderId,
        UUID ownerId,
        UUID facilityId,
        List<AssignedStockMoveLine> moves,
        Instant dispatchBy,
        int releasePriority,
        Instant assignedAt) {

    public RecordStockOperationAssignedCommand {
        moves = List.copyOf(moves);
    }
}
