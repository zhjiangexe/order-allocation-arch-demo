package com.flowzati.archone.ordering.domain.model;

import com.flowzati.archone.common.ddd.DomainEvent;
import com.flowzati.archone.ordering.domain.event.OrderAllocated;
import com.flowzati.archone.ordering.domain.event.OrderBackordered;
import com.flowzati.archone.ordering.domain.event.OrderCancelled;
import com.flowzati.archone.ordering.domain.event.OrderPlaced;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class Order {
  private final List<DomainEvent> events = new ArrayList<>();
  private final UUID id;
  private final String sku;
  private final int quantity;
  private final Instant placedAt;
  private final Long version;
  private OrderStatus status;
  private Instant allocatedAt;
  private Instant backOrderedSince;
  private Instant cancelledAt;

  private Order(
      UUID id,
      String sku,
      int quantity,
      OrderStatus status,
      Instant placedAt,
      Instant allocatedAt,
      Instant backOrderedSince,
      Instant cancelledAt,
      Long version
  ) {
    validateState(id, sku, quantity, status, placedAt, allocatedAt, backOrderedSince, cancelledAt, version);
    this.id = id;
    this.sku = sku;
    this.quantity = quantity;
    this.status = status;
    this.placedAt = placedAt;
    this.allocatedAt = allocatedAt;
    this.backOrderedSince = backOrderedSince;
    this.cancelledAt = cancelledAt;
    this.version = version;
  }

  public static Order place(UUID id, String sku, int quantity, Instant placedAt) {
    Order order = new Order(
        id, sku, quantity, OrderStatus.PENDING, placedAt, null, null, null, null);
    order.events.add(new OrderPlaced(id, sku, quantity, placedAt));
    return order;
  }

  public static Order rehydrate(
      UUID id,
      String sku,
      int quantity,
      OrderStatus status,
      Instant placedAt,
      Instant allocatedAt,
      Instant backOrderedSince,
      Instant cancelledAt,
      Long version
  ) {
    return new Order(
        id, sku, quantity, status, placedAt, allocatedAt, backOrderedSince, cancelledAt, version);
  }

  public void markAllocated(Instant allocatedAt) {
    if (status != OrderStatus.PENDING && status != OrderStatus.BACKORDERED) {
      throw new IllegalStateException("Only pending or backordered orders can be allocated");
    }
    requireNotBefore(allocatedAt, placedAt, "Allocated time cannot be before placed time");
    if (backOrderedSince != null) {
      requireNotBefore(
          allocatedAt, backOrderedSince, "Allocated time cannot be before backordered time");
    }

    status = OrderStatus.ALLOCATED;
    this.allocatedAt = allocatedAt;
    events.add(new OrderAllocated(id, allocatedAt));
  }

  public void markBackOrdered(Instant backorderedSince) {
    if (status != OrderStatus.PENDING) {
      throw new IllegalStateException("Only pending orders can be backordered");
    }
    requireNotBefore(
        backorderedSince, placedAt, "Backordered time cannot be before placed time");

    status = OrderStatus.BACKORDERED;
    this.backOrderedSince = backorderedSince;
    events.add(new OrderBackordered(id, sku, quantity, backorderedSince));
  }

  public boolean cancel(Instant cancelledAt) {
    if (status == OrderStatus.CANCELLED) {
      return false;
    }
    requireNotBefore(cancelledAt, placedAt, "Cancelled time cannot be before placed time");
    if (allocatedAt != null) {
      requireNotBefore(cancelledAt, allocatedAt, "Cancelled time cannot be before allocated time");
    }
    if (backOrderedSince != null) {
      requireNotBefore(
          cancelledAt, backOrderedSince, "Cancelled time cannot be before backordered time");
    }

    status = OrderStatus.CANCELLED;
    this.cancelledAt = cancelledAt;
    events.add(new OrderCancelled(id, cancelledAt));
    return true;
  }

  public List<DomainEvent> releaseDomainEvents() {
    List<DomainEvent> domainEvents = List.copyOf(events);
    events.clear();
    return domainEvents;
  }

  private static void validateState(
      UUID id,
      String sku,
      int quantity,
      OrderStatus status,
      Instant placedAt,
      Instant allocatedAt,
      Instant backOrderedSince,
      Instant cancelledAt,
      Long version
  ) {
    if (id == null) {
      throw new IllegalArgumentException("Order ID is required");
    }
    if (sku == null || sku.isBlank()) {
      throw new IllegalArgumentException("SKU is required");
    }
    if (quantity <= 0) {
      throw new IllegalArgumentException("Order quantity must be positive");
    }
    if (status == null) {
      throw new IllegalArgumentException("Order status is required");
    }
    if (placedAt == null) {
      throw new IllegalArgumentException("Placed time is required");
    }
    if (version != null && version < 0) {
      throw new IllegalArgumentException("Version cannot be negative");
    }
    if (allocatedAt != null) {
      requireNotBefore(allocatedAt, placedAt, "Allocated time cannot be before placed time");
    }
    if (backOrderedSince != null) {
      requireNotBefore(
          backOrderedSince, placedAt, "Backordered time cannot be before placed time");
    }
    if (allocatedAt != null && backOrderedSince != null) {
      requireNotBefore(
          allocatedAt, backOrderedSince, "Allocated time cannot be before backordered time");
    }
    if (cancelledAt != null) {
      requireNotBefore(cancelledAt, placedAt, "Cancelled time cannot be before placed time");
      if (allocatedAt != null) {
        requireNotBefore(cancelledAt, allocatedAt, "Cancelled time cannot be before allocated time");
      }
      if (backOrderedSince != null) {
        requireNotBefore(
            cancelledAt, backOrderedSince, "Cancelled time cannot be before backordered time");
      }
    }

    switch (status) {
      case PENDING -> require(
          allocatedAt == null && backOrderedSince == null && cancelledAt == null,
          "Pending order cannot contain transition timestamps");
      case ALLOCATED -> require(
          allocatedAt != null && cancelledAt == null,
          "Allocated order requires allocated time and cannot contain cancelled time");
      case BACKORDERED -> require(
          backOrderedSince != null && allocatedAt == null && cancelledAt == null,
          "Backordered order requires backordered time only");
      case CANCELLED -> require(cancelledAt != null, "Cancelled order requires cancelled time");
    }
  }

  private static void requireNotBefore(Instant value, Instant lowerBound, String message) {
    if (value == null) {
      throw new IllegalArgumentException("Transition time is required");
    }
    if (value.isBefore(lowerBound)) {
      throw new IllegalArgumentException(message);
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalArgumentException(message);
    }
  }

  public UUID getId() {
    return id;
  }

  public String getSku() {
    return sku;
  }

  public int getQuantity() {
    return quantity;
  }

  public OrderStatus getStatus() {
    return status;
  }

  public Instant getPlacedAt() {
    return placedAt;
  }

  public Instant getAllocatedAt() {
    return allocatedAt;
  }

  public Instant getBackOrderedSince() {
    return backOrderedSince;
  }

  public Instant getCancelledAt() {
    return cancelledAt;
  }

  public Long getVersion() {
    return version;
  }
}
