package com.flowzati.archone.ordering.infrastructure.mapper;

import com.flowzati.archone.ordering.domain.model.Order;
import com.flowzati.archone.ordering.infrastructure.entity.OrderEntity;

public final class OrderMapper {

  private OrderMapper() {
  }

  public static OrderEntity toEntity(Order order) {
    return new OrderEntity(
        order.getId(),
        order.getSku(),
        order.getQuantity(),
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
        entity.getSku(),
        entity.getQuantity(),
        entity.getStatus(),
        entity.getPlacedAt(),
        entity.getAllocatedAt(),
        entity.getBackorderedSince(),
        entity.getCancelledAt(),
        entity.getVersion()
    );
  }
}
