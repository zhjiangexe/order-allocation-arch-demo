package com.flowzati.archone.demo.fulfillment;

import com.flowzati.archone.ordering.domain.aggregate.Order;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** 跨 Context 履約畫面需要的 Ordering-owned facts。 */
public record FulfillmentOrderView(
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
        List<FulfillmentOrderLineView> lines) {

    public FulfillmentOrderView {
        lines = List.copyOf(lines);
    }

    static FulfillmentOrderView from(Order order) {
        var delivery = order.getDeliveryTerms();
        return new FulfillmentOrderView(
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
                        .map(line -> new FulfillmentOrderLineView(
                                line.getId(), line.getLineNo(), line.getSkuCode(), line.getQuantity()))
                        .toList());
    }
}
