package com.flowzati.archone.demo.orderfulfillment.result;

import com.flowzati.archone.ordering.domain.aggregate.Order;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** 跨 Context 履約畫面需要的 Ordering-owned facts。 */
public record OrderView(
        UUID orderId,
        UUID ownerId,
        String externalOrderNo,
        UUID facilityId,
        String shipToZone,
        String shipToAddress,
        LocalDate promisedDeliveryDate,
        Instant dispatchBy,
        int releasePriority,
        String status,
        Instant receivedAt,
        Instant placedAt,
        Instant allocatedAt,
        Instant cancelledAt,
        UUID cancellationRequestId,
        String cancellationReason,
        Instant fulfilledAt,
        UUID fulfilledByShipmentId,
        List<OrderLineView> lines) {

    public OrderView {
        lines = List.copyOf(lines);
    }

    public static OrderView from(Order order) {
        var delivery = order.getDeliveryTerms();
        return new OrderView(
                order.getId(),
                order.getOwnerId(),
                order.getExternalOrderNo(),
                delivery.facilityId(),
                delivery.shipToZone(),
                delivery.shipToAddress(),
                delivery.promisedDeliveryDate(),
                delivery.dispatchBy(),
                delivery.releasePriority(),
                order.getStatus().name(),
                order.getReceivedAt(),
                order.getPlacedAt(),
                order.getAllocatedAt(),
                order.getCancelledAt(),
                order.getCancellationRequestId(),
                order.getCancellationReason(),
                order.getFulfilledAt(),
                order.getFulfilledByShipmentId(),
                order.getLines().stream()
                        .map(line -> new OrderLineView(
                                line.getId(), line.getLineNo(), line.getSkuCode(), line.getQuantity()))
                        .toList());
    }
}
