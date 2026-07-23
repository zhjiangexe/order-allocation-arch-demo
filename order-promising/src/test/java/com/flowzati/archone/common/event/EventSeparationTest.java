package com.flowzati.archone.common.event;

import com.flowzati.archone.allocation.application.event.BackorderCreatedIntegrationEvent;
import com.flowzati.archone.allocation.application.event.OrderAllocatedIntegrationEvent;
import com.flowzati.archone.allocation.application.event.StockReplenishedIntegrationEvent;
import com.flowzati.archone.common.ddd.DomainEvent;
import com.flowzati.archone.common.integration.IntegrationEvent;
import com.flowzati.archone.ordering.domain.event.OrderPlaced;
import com.flowzati.archone.ordering.application.event.OrderCancelledIntegrationEvent;
import com.flowzati.archone.ordering.application.event.OrderPlacedIntegrationEvent;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventSeparationTest {

  private final UUID eventId = UUID.randomUUID();
  private final UUID orderId = UUID.randomUUID();
  private final Instant occurredAt = Instant.parse("2026-07-23T00:00:00Z");

  @Test
  void domainEventShouldBeAnInternalMarkerWithoutMessagingIdentity() {
    OrderPlaced event =
        new OrderPlaced(orderId, "SKU-1", 3, occurredAt);

    assertThat(event).isInstanceOf(DomainEvent.class);
    assertThat(event.getClass().getMethods())
        .noneMatch(method -> method.getName().equals("getEventId"));
  }

  @Test
  void integrationEventShouldOwnImmutableEventId() throws NoSuchFieldException {
    assertThat(Modifier.isFinal(
        IntegrationEvent.class.getDeclaredField("eventId").getModifiers())).isTrue();
    assertThat(IntegrationEvent.class.getMethods())
        .noneMatch(method -> method.getName().equals("setEventId"));
    assertThatThrownBy(() -> new StockReplenishedIntegrationEvent(null, "SKU-1", 1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void shouldExposeCompleteOrderingIntegrationEventContracts() {
    OrderPlacedIntegrationEvent placed = new OrderPlacedIntegrationEvent(eventId, orderId, "SKU-1", 3, occurredAt);
    OrderCancelledIntegrationEvent cancelled = new OrderCancelledIntegrationEvent(eventId, orderId, occurredAt);

    assertThat(placed.getEventId()).isEqualTo(eventId);
    assertThat(placed.getOrderId()).isEqualTo(orderId);
    assertThat(placed.getSku()).isEqualTo("SKU-1");
    assertThat(placed.getQuantity()).isEqualTo(3);
    assertThat(placed.getPlacedAt()).isEqualTo(occurredAt);
    assertThat(cancelled.getCancelledAt()).isEqualTo(occurredAt);
  }

  @Test
  void shouldExposeCompleteAllocationIntegrationEventContracts() {
    UUID reservationId = UUID.randomUUID();
    OrderAllocatedIntegrationEvent allocated = new OrderAllocatedIntegrationEvent(
        eventId, orderId, reservationId, "SKU-1", 3, occurredAt);
    BackorderCreatedIntegrationEvent backorder = new BackorderCreatedIntegrationEvent(
        eventId, orderId, "SKU-1", 3, occurredAt);
    StockReplenishedIntegrationEvent replenished = new StockReplenishedIntegrationEvent(eventId, "SKU-1", 10);

    assertThat(allocated.getReservationId()).isEqualTo(reservationId);
    assertThat(allocated.getAllocatedAt()).isEqualTo(occurredAt);
    assertThat(backorder.getBackorderedSince()).isEqualTo(occurredAt);
    assertThat(replenished.getQuantity()).isEqualTo(10);
  }

  @Test
  void shouldRejectInvalidIntegrationEventPayloads() {
    assertThatThrownBy(() -> new OrderPlacedIntegrationEvent(eventId, orderId, "", 1, occurredAt))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new StockReplenishedIntegrationEvent(eventId, "SKU-1", 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new OrderAllocatedIntegrationEvent(
        eventId, orderId, null, "SKU-1", 1, occurredAt))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
