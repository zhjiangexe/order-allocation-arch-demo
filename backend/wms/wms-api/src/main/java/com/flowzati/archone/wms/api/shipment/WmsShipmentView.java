package com.flowzati.archone.wms.api.shipment;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Public read model for shipments belonging to an order. */
public record WmsShipmentView(
        UUID shipmentId,
        UUID stockOperationId,
        UUID orderId,
        UUID ownerId,
        UUID facilityId,
        String status,
        UUID waveId,
        Instant createdAt,
        Instant dispatchBy,
        int releasePriority,
        UUID cancellationRequestId,
        Instant cancellationRequestedAt,
        String cancellationReason,
        Instant cancelledAt,
        String cancellationState,
        List<WmsShipmentLineView> lines,
        List<WmsPickTaskView> pickTasks) {

    public WmsShipmentView {
        lines = List.copyOf(lines);
        pickTasks = List.copyOf(pickTasks);
    }
}
