package com.flowzati.archone.ordering.infrastructure.mapper;

import com.flowzati.archone.ordering.domain.model.Order;
import com.flowzati.archone.ordering.domain.model.DeliveryTerms;
import com.flowzati.archone.ordering.domain.model.OrderLine;
import com.flowzati.archone.ordering.infrastructure.entity.OrderEntity;
import com.flowzati.archone.ordering.infrastructure.entity.OrderLineEntity;
import java.util.List;

public final class OrderMapper {

  private OrderMapper() {
  }

  public static OrderEntity toEntity(Order order) {
    DeliveryTerms delivery = order.getDeliveryTerms();
    return new OrderEntity(
        order.getId(),
        order.getOwnerId(),
        order.getExternalOrderNo(),
        delivery.shipToZone(),
        delivery.shipToAddress(),
        delivery.promisedDeliveryDate(),
        delivery.requestedNodeId(),
        toLineEntities(order.getLines()),
        order.getStatus(),
        order.getPlacedAt(),
        order.getAllocatedAt(),
        order.getBackOrderedSince(),
        order.getCancelledAt(),
        order.getVersion()
    );
  }

  public static Order toDomain(OrderEntity entity) {
    return Order.rehydrate(
        entity.getId(),
        entity.getOwnerId(),
        entity.getExternalOrderNo(),
        new DeliveryTerms(
            entity.getShipToZone(),
            entity.getShipToAddress(),
            entity.getPromisedDeliveryDate(),
            entity.getRequestedNodeId()),
        toDomainLines(entity.getLines()),
        entity.getStatus(),
        entity.getPlacedAt(),
        entity.getAllocatedAt(),
        entity.getBackorderedSince(),
        entity.getCancelledAt(),
        entity.getVersion()
    );
  }

  private static List<OrderLineEntity> toLineEntities(List<OrderLine> lines) {
    return lines.stream()
        .map(line -> new OrderLineEntity(
            line.getId(),
            line.getLineNo(),
            line.getOwnerId(),
            line.getSkuCode(),
            line.getQuantity(),
            line.getStatus(),
            line.getBackorderedSince(),
            line.getAssignedNodeId()))
        .toList();
  }

  private static List<OrderLine> toDomainLines(List<OrderLineEntity> lines) {
    return lines.stream()
        .map(line -> OrderLine.rehydrate(
            line.getId(),
            line.getLineNo(),
            line.getOwnerId(),
            line.getSkuCode(),
            line.getQuantity(),
            line.getStatus(),
            line.getBackorderedSince(),
            line.getAssignedNodeId()))
        .toList();
  }
}
