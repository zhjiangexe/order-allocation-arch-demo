package com.flowzati.archone.ordering.infrastructure.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowzati.archone.ordering.domain.model.Order;
import com.flowzati.archone.ordering.domain.model.OrderStatus;
import com.flowzati.archone.ordering.infrastructure.entity.OrderEntity;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OrderMapperTest {

  private static final UUID ORDER_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final Instant PLACED_AT = Instant.parse("2026-07-23T08:00:00Z");
  private static final Instant BACKORDERED_AT = Instant.parse("2026-07-23T08:01:00Z");

  @Test
  @DisplayName("應將完整 Order domain state 與 version 映射到 entity")
  void mapsDomainToEntity() {
    Order order = Order.rehydrate(
        ORDER_ID,
        "SKU-1",
        3,
        OrderStatus.BACKORDERED,
        PLACED_AT,
        null,
        BACKORDERED_AT,
        null,
        7L
    );

    OrderEntity entity = OrderMapper.toEntity(order);

    assertThat(entity.getId()).isEqualTo(ORDER_ID);
    assertThat(entity.getSku()).isEqualTo("SKU-1");
    assertThat(entity.getQuantity()).isEqualTo(3);
    assertThat(entity.getStatus()).isEqualTo(OrderStatus.BACKORDERED);
    assertThat(entity.getPlacedAt()).isEqualTo(PLACED_AT);
    assertThat(entity.getAllocatedAt()).isNull();
    assertThat(entity.getBackorderedSince()).isEqualTo(BACKORDERED_AT);
    assertThat(entity.getCancelledAt()).isNull();
    assertThat(entity.getVersion()).isEqualTo(7L);
  }

  @Test
  @DisplayName("應以 rehydrate 還原 Order 且不產生 domain event")
  void mapsEntityToDomainWithoutProducingDomainEvents() {
    Instant allocatedAt = Instant.parse("2026-07-23T08:02:00Z");
    Instant cancelledAt = Instant.parse("2026-07-23T08:03:00Z");
    OrderEntity entity = new OrderEntity(
        ORDER_ID,
        "SKU-1",
        3,
        OrderStatus.CANCELLED,
        PLACED_AT,
        allocatedAt,
        null,
        cancelledAt,
        4L
    );

    Order order = OrderMapper.toDomain(entity);

    assertThat(order.getId()).isEqualTo(ORDER_ID);
    assertThat(order.getSku()).isEqualTo("SKU-1");
    assertThat(order.getQuantity()).isEqualTo(3);
    assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    assertThat(order.getPlacedAt()).isEqualTo(PLACED_AT);
    assertThat(order.getAllocatedAt()).isEqualTo(allocatedAt);
    assertThat(order.getBackOrderedSince()).isNull();
    assertThat(order.getCancelledAt()).isEqualTo(cancelledAt);
    assertThat(order.getVersion()).isEqualTo(4L);
    assertThat(order.releaseDomainEvents()).isEmpty();
  }
}
