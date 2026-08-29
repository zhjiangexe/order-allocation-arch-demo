package com.flowzati.archone.wms.outbound.application.query;

import com.flowzati.archone.wms.outbound.domain.aggregate.Shipment;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** DEMO 履約追蹤使用的 WMS Shipment 唯讀視圖。 */
public record ShipmentView(
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
        List<ShipmentLineView> lines,
        List<PickTaskView> pickTasks) {

    public ShipmentView {
        lines = List.copyOf(lines);
        pickTasks = List.copyOf(pickTasks);
    }

    public static ShipmentView from(Shipment shipment) {
        return new ShipmentView(
                shipment.id(),
                shipment.stockOperationId(),
                shipment.orderId(),
                shipment.ownerId(),
                shipment.facilityId(),
                shipment.status().name(),
                shipment.waveId(),
                shipment.createdAt(),
                shipment.dispatchBy(),
                shipment.releasePriority(),
                shipment.cancellationRequestId(),
                shipment.cancellationRequestedAt(),
                shipment.cancellationReason(),
                shipment.cancelledAt(),
                shipment.cancellationStateValue().map(Enum::name).orElse(null),
                shipment.lines().stream()
                        .map(line -> new ShipmentLineView(
                                line.orderLineId(),
                                line.moveId(),
                                line.skuCode(),
                                line.sourceLocationId(),
                                line.quantity()))
                        .toList(),
                shipment.pickTasks().stream()
                        .map(task -> new PickTaskView(
                                task.id(),
                                task.orderLineId(),
                                task.moveId(),
                                task.skuCode(),
                                task.sourceLocationId(),
                                task.requestedQuantity(),
                                task.pickedQuantity(),
                                task.status().name(),
                                task.confirmedAt()))
                        .toList());
    }
}
