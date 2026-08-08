package com.flowzati.archone.contracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowzati.archone.contracts.inventory.v1.StockAvailabilityIncreasedIntegrationEvent;
import com.flowzati.archone.contracts.ordering.v1.OrderCancelledIntegrationEvent;
import com.flowzati.archone.contracts.ordering.v1.OrderPlacedIntegrationEvent;
import com.flowzati.archone.contracts.promising.v1.BackorderCreatedIntegrationEvent;
import com.flowzati.archone.contracts.promising.v1.OrderAllocatedIntegrationEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IntegrationEventContractTest {

  private static final UUID EVENT_ID = new UUID(0, 1);
  private static final UUID ORDER_ID = new UUID(0, 2);
  private static final Instant OCCURRED_AT = Instant.parse("2026-08-07T00:00:00Z");

  @Test
  void keepsLifecycleEventIdentityAndTimesExplicit() {
    var placed = new OrderPlacedIntegrationEvent(EVENT_ID, ORDER_ID, OCCURRED_AT);
    var cancelled = new OrderCancelledIntegrationEvent(EVENT_ID, ORDER_ID, OCCURRED_AT);
    var allocated = new OrderAllocatedIntegrationEvent(EVENT_ID, ORDER_ID, OCCURRED_AT);
    var backordered = new BackorderCreatedIntegrationEvent(EVENT_ID, ORDER_ID, OCCURRED_AT);

    assertThat(placed.getEventId()).isEqualTo(EVENT_ID);
    assertThat(placed.getOrderId()).isEqualTo(ORDER_ID);
    assertThat(placed.getReceivedAt()).isEqualTo(OCCURRED_AT);
    assertThat(cancelled.getCancelledAt()).isEqualTo(OCCURRED_AT);
    assertThat(allocated.getAllocatedAt()).isEqualTo(OCCURRED_AT);
    assertThat(backordered.getBackorderedSince()).isEqualTo(OCCURRED_AT);
  }

  @Test
  void validatesInventoryAvailabilityContractAtItsBoundary() {
    UUID ownerId = new UUID(0, 3);
    UUID facilityId = new UUID(0, 4);
    UUID locationId = new UUID(0, 5);
    var event = new StockAvailabilityIncreasedIntegrationEvent(
        EVENT_ID, ownerId, facilityId, locationId, "SKU-1", 3);

    assertThat(event.getOwnerId()).isEqualTo(ownerId);
    assertThat(event.getFacilityId()).isEqualTo(facilityId);
    assertThat(event.getLocationId()).isEqualTo(locationId);
    assertThat(event.getSku()).isEqualTo("SKU-1");
    assertThat(event.getQuantity()).isEqualTo(3);
    assertThatThrownBy(() -> new StockAvailabilityIncreasedIntegrationEvent(
        EVENT_ID, ownerId, facilityId, locationId, "SKU-1", 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void keepsWireEventTypesStableAndUnique() {
    List<String> eventTypes = List.of(
        OrderPlacedIntegrationEvent.EVENT_TYPE,
        OrderCancelledIntegrationEvent.EVENT_TYPE,
        OrderAllocatedIntegrationEvent.EVENT_TYPE,
        BackorderCreatedIntegrationEvent.EVENT_TYPE,
        StockAvailabilityIncreasedIntegrationEvent.EVENT_TYPE);

    assertThat(eventTypes).containsExactly(
        "OrderPlacedIntegrationEvent",
        "OrderCancelledIntegrationEvent",
        "OrderAllocatedIntegrationEvent",
        "BackorderCreatedIntegrationEvent",
        "StockAvailabilityIncreasedIntegrationEvent");
    assertThat(eventTypes).doesNotHaveDuplicates();
  }
}
