package com.flowzati.archone.ordering.infrastructure.mapper;

import com.flowzati.archone.ordering.domain.aggregate.Order;
import com.flowzati.archone.ordering.domain.entity.OrderLine;
import com.flowzati.archone.ordering.domain.valueobject.DeliveryTerms;
import com.flowzati.archone.ordering.infrastructure.entity.OrderEntity;
import com.flowzati.archone.ordering.infrastructure.entity.OrderLineEntity;
import java.util.List;

public final class OrderMapper {

    private OrderMapper() {}

    public static OrderEntity toEntity(Order order) {
        DeliveryTerms delivery = order.getDeliveryTerms();
        return new OrderEntity(
                order.getId(),
                order.getOwnerId(),
                order.getExternalOrderNo(),
                delivery.shipToZone(),
                delivery.shipToAddress(),
                delivery.promisedDeliveryDate(),
                delivery.dispatchBy(),
                delivery.releasePriority(),
                delivery.facilityId(),
                toLineEntities(order.getLines()),
                order.getStatus(),
                order.getReceivedAt(),
                order.getPlacedAt(),
                order.getAllocatedAt(),
                null,
                order.getCancelledAt(),
                order.getFulfilledAt(),
                order.getVersion());
    }

    public static Order toDomain(OrderEntity entity) {
        return Order.rehydrate(
                entity.getId(),
                entity.getOwnerId(),
                entity.getExternalOrderNo(),
                new DeliveryTerms(
                        entity.getFacilityId(),
                        entity.getShipToZone(),
                        entity.getShipToAddress(),
                        entity.getPromisedDeliveryDate(),
                        entity.getDispatchBy(),
                        entity.getReleasePriority()),
                toDomainLines(entity.getLines()),
                entity.getStatus(),
                entity.getReceivedAt(),
                entity.getPlacedAt(),
                entity.getAllocatedAt(),
                null,
                entity.getCancelledAt(),
                entity.getFulfilledAt(),
                entity.getVersion());
    }

    private static List<OrderLineEntity> toLineEntities(List<OrderLine> lines) {
        return lines.stream()
                .map(line -> new OrderLineEntity(
                        line.getId(), line.getLineNo(), line.getOwnerId(), line.getSkuCode(), line.getQuantity()))
                .toList();
    }

    private static List<OrderLine> toDomainLines(List<OrderLineEntity> lines) {
        return lines.stream()
                .map(line -> OrderLine.create(
                        line.getId(), line.getLineNo(), line.getOwnerId(), line.getSkuCode(), line.getQuantity()))
                .toList();
    }
}
