package com.flowzati.archone.ordering.domain.model;

import com.flowzati.archone.ordering.domain.event.OrderAllocated;
import com.flowzati.archone.ordering.domain.event.OrderBackordered;
import com.flowzati.archone.ordering.domain.event.OrderCancelled;
import com.flowzati.archone.ordering.domain.event.OrderPlaced;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderTest {

  private final UUID orderId = UUID.randomUUID();
  private final Instant placedAt = Instant.parse("2026-07-23T00:00:00Z");

  @Test
  void shouldPlacePendingOrderAndRecordDomainEvent() {
    Order order = Order.place(orderId, "SKU-1", 3, placedAt);

    assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
    assertThat(order.getVersion()).isNull();
    assertThat(order.releaseDomainEvents()).containsExactly(
        new OrderPlaced(orderId, "SKU-1", 3, placedAt));
    assertThat(order.releaseDomainEvents()).isEmpty();
  }

  @Test
  void shouldAllocatePendingOrder() {
    Instant allocatedAt = placedAt.plusSeconds(10);
    Order order = pendingOrder();

    order.markAllocated(allocatedAt);

    assertThat(order.getStatus()).isEqualTo(OrderStatus.ALLOCATED);
    assertThat(order.getAllocatedAt()).isEqualTo(allocatedAt);
    assertThat(order.releaseDomainEvents()).containsExactly(
        new OrderAllocated(orderId, allocatedAt));
  }

  @Test
  void shouldBackorderPendingOrder() {
    Instant backorderedAt = placedAt.plusSeconds(10);
    Order order = pendingOrder();

    order.markBackOrdered(backorderedAt);

    assertThat(order.getStatus()).isEqualTo(OrderStatus.BACKORDERED);
    assertThat(order.getBackOrderedSince()).isEqualTo(backorderedAt);
    assertThat(order.releaseDomainEvents()).containsExactly(
        new OrderBackordered(orderId, "SKU-1", 3, backorderedAt));
  }

  @Test
  void shouldAllocateBackorderedOrderAndPreserveHistory() {
    Instant backorderedAt = placedAt.plusSeconds(10);
    Instant allocatedAt = placedAt.plusSeconds(20);
    Order order = pendingOrder();
    order.markBackOrdered(backorderedAt);
    order.releaseDomainEvents();

    order.markAllocated(allocatedAt);

    assertThat(order.getStatus()).isEqualTo(OrderStatus.ALLOCATED);
    assertThat(order.getBackOrderedSince()).isEqualTo(backorderedAt);
    assertThat(order.getAllocatedAt()).isEqualTo(allocatedAt);
  }

  @Test
  void shouldCancelOrderOnlyOnce() {
    Instant cancelledAt = placedAt.plusSeconds(10);
    Order order = pendingOrder();

    assertThat(order.cancel(cancelledAt)).isTrue();
    assertThat(order.cancel(cancelledAt.plusSeconds(1))).isFalse();

    assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    assertThat(order.getCancelledAt()).isEqualTo(cancelledAt);
    assertThat(order.releaseDomainEvents()).containsExactly(
        new OrderCancelled(orderId, cancelledAt));
  }

  @Test
  void shouldAllowAllocatedOrderToBeCancelled() {
    Order order = pendingOrder();
    order.markAllocated(placedAt.plusSeconds(10));
    order.releaseDomainEvents();

    order.cancel(placedAt.plusSeconds(20));

    assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    assertThat(order.getAllocatedAt()).isEqualTo(placedAt.plusSeconds(10));
  }

  @Test
  void shouldRejectIllegalTransitions() {
    Order allocated = pendingOrder();
    allocated.markAllocated(placedAt.plusSeconds(1));

    assertThatThrownBy(() -> allocated.markBackOrdered(placedAt.plusSeconds(2)))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> allocated.markAllocated(placedAt.plusSeconds(2)))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void shouldRejectTransitionTimeBeforeLifecycleHistory() {
    Order order = pendingOrder();

    assertThatThrownBy(() -> order.markAllocated(placedAt.minusSeconds(1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> order.markBackOrdered(placedAt.minusSeconds(1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> order.cancel(placedAt.minusSeconds(1)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void shouldRejectInvalidOrderCreation() {
    assertThatThrownBy(() -> Order.place(null, "SKU-1", 1, placedAt))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Order.place(orderId, " ", 1, placedAt))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Order.place(orderId, "SKU-1", 0, placedAt))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Order.place(orderId, "SKU-1", 1, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void shouldRehydrateWithoutRecordingDomainEvents() {
    Instant backorderedAt = placedAt.plusSeconds(10);
    Instant allocatedAt = placedAt.plusSeconds(20);

    Order order = Order.rehydrate(
        orderId,
        "SKU-1",
        3,
        OrderStatus.ALLOCATED,
        placedAt,
        allocatedAt,
        backorderedAt,
        null,
        4L
    );

    assertThat(order.getVersion()).isEqualTo(4L);
    assertThat(order.getBackOrderedSince()).isEqualTo(backorderedAt);
    assertThat(order.releaseDomainEvents()).isEmpty();
  }

  @Test
  void shouldRejectInconsistentRehydratedState() {
    assertThatThrownBy(() -> Order.rehydrate(
        orderId, "SKU-1", 3, OrderStatus.ALLOCATED,
        placedAt, null, null, null, 0L))
        .isInstanceOf(IllegalArgumentException.class);

    assertThatThrownBy(() -> Order.rehydrate(
        orderId, "SKU-1", 3, OrderStatus.PENDING,
        placedAt, placedAt.plusSeconds(1), null, null, 0L))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private Order pendingOrder() {
    Order order = Order.place(orderId, "SKU-1", 3, placedAt);
    order.releaseDomainEvents();
    return order;
  }
}
