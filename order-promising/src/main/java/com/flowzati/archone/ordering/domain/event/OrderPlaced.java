package com.flowzati.archone.ordering.domain.event;

import com.flowzati.archone.common.ddd.DomainEvent;

import java.util.UUID;

public class OrderPlaced extends DomainEvent {
  private UUID orderId;

  public OrderPlaced() {
  }

  public OrderPlaced(UUID orderId) {
    this.orderId = orderId;
  }

  public OrderPlaced(UUID eventId, UUID orderId) {
    this.setEventId(eventId);
    this.orderId = orderId;
  }

  public UUID getOrderId() {
    return orderId;
  }
}
