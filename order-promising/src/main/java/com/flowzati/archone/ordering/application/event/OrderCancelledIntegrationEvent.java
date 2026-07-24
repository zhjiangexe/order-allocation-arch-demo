package com.flowzati.archone.ordering.application.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.flowzati.archone.common.integration.IntegrationEvent;
import java.time.Instant;
import java.util.UUID;

public final class OrderCancelledIntegrationEvent extends IntegrationEvent {
  private final UUID orderId;
  private final Instant cancelledAt;

  @JsonCreator
  public OrderCancelledIntegrationEvent(
      @JsonProperty("eventId") UUID eventId,
      @JsonProperty("orderId") UUID orderId,
      @JsonProperty("cancelledAt") Instant cancelledAt
  ) {
    super(eventId);
    if (orderId == null) {
      throw new IllegalArgumentException("Order ID is required");
    }
    if (cancelledAt == null) {
      throw new IllegalArgumentException("Cancelled time is required");
    }
    this.orderId = orderId;
    this.cancelledAt = cancelledAt;
  }

  public UUID getOrderId() {
    return orderId;
  }

  public Instant getCancelledAt() {
    return cancelledAt;
  }
}
