package com.flowzati.archone.ordering.domain.model;

import com.flowzati.archone.common.ddd.DomainEvent;
import com.flowzati.archone.ordering.domain.event.BackorderCreated;
import com.flowzati.archone.ordering.domain.event.OrderPlaced;
import com.flowzati.archone.ordering.domain.event.OrderAllocated;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class Order {
  private final List<DomainEvent> events = new ArrayList<>();
  private final UUID id;
  private final String sku;
  private final int quantity;
  private OrderStatus status;
  private Instant placedAt;
  private Instant allocatedAt;
  private Instant backOrderedSince;
  private Instant cancelledAt;

  private Order(UUID id, String sku, int quantity) {
    this.id = id;
    this.sku = sku;
    this.quantity = quantity;
  }

  public static Order place(UUID uuid, String sku, int quantity) {
    Order order = new Order(uuid, sku, quantity);
    order.status = OrderStatus.PENDING;
    order.placedAt = Instant.now();
    order.events.add(new OrderPlaced());
    return order;
  }

  public void markAllocated(Instant now) {
    this.status = OrderStatus.ALLOCATED;
    this.allocatedAt = now;
    this.events.add(new OrderAllocated());
  }

  public void markBackOrdered(Instant now) {
    this.status = OrderStatus.BACKORDERED;
    this.backOrderedSince = now;
    this.events.add(new BackorderCreated());
  }

  public List<DomainEvent> releaseDomainEvents() {
    List<DomainEvent> domainEvents = List.copyOf(events);
    events.clear();
    return domainEvents;
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

}
