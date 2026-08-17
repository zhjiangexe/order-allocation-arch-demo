package com.flowzati.archone.contracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowzati.archone.contracts.fulfillment.v1.AllocationCommittedForFulfillmentIntegrationEvent;
import com.flowzati.archone.contracts.inventory.v1.StockAvailabilityIncreasedIntegrationEvent;
import com.flowzati.archone.contracts.ordering.v1.OrderCancelledIntegrationEvent;
import com.flowzati.archone.contracts.ordering.v1.OrderPlacedIntegrationEvent;
import com.flowzati.archone.contracts.promising.v1.OrderAllocatedIntegrationEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IntegrationEventContractTest {

  private static final UUID EVENT_ID = new UUID(0, 1);
  private static final UUID ORDER_ID = new UUID(0, 2);
  private static final UUID ALLOCATION_ID = new UUID(0, 6);
  private static final UUID ORDER_LINE_ID = new UUID(0, 7);
  private static final UUID MOVE_ID = new UUID(0, 8);
  private static final Instant OCCURRED_AT = Instant.parse("2026-08-07T00:00:00Z");

  @Test
  void keepsLifecycleEventIdentityAndTimesExplicit() {
    var placed = new OrderPlacedIntegrationEvent(EVENT_ID, ORDER_ID, OCCURRED_AT);
    var cancelled = new OrderCancelledIntegrationEvent(EVENT_ID, ORDER_ID, OCCURRED_AT);
    var allocated = new OrderAllocatedIntegrationEvent(EVENT_ID, ORDER_ID, OCCURRED_AT);

    assertThat(placed.getEventId()).isEqualTo(EVENT_ID);
    assertThat(placed.getOrderId()).isEqualTo(ORDER_ID);
    assertThat(placed.getReceivedAt()).isEqualTo(OCCURRED_AT);
    assertThat(cancelled.getCancelledAt()).isEqualTo(OCCURRED_AT);
    assertThat(allocated.getAllocatedAt()).isEqualTo(OCCURRED_AT);
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
  void providesACompleteStandaloneFulfillmentHandoff() {
    UUID ownerId = new UUID(0, 3);
    UUID facilityId = new UUID(0, 4);
    UUID locationId = new UUID(0, 5);
    Instant dispatchBy = OCCURRED_AT.plusSeconds(3600);
    var event = new AllocationCommittedForFulfillmentIntegrationEvent(
        EVENT_ID,
        ALLOCATION_ID,
        ORDER_ID,
        ownerId,
        facilityId,
        List.of(new AllocationCommittedForFulfillmentIntegrationEvent.AllocationLine(
            ORDER_LINE_ID, MOVE_ID, "SKU-1", locationId, 3)),
        dispatchBy,
        80,
        OCCURRED_AT);

    assertThat(event.getAllocationId()).isEqualTo(ALLOCATION_ID);
    assertThat(event.getOrderId()).isEqualTo(ORDER_ID);
    assertThat(event.getLines()).hasSize(1);
    assertThat(event.getDispatchBy()).isEqualTo(dispatchBy);
    assertThat(event.getReleasePriority()).isEqualTo(80);
    assertThatThrownBy(() -> new AllocationCommittedForFulfillmentIntegrationEvent(
        EVENT_ID, ALLOCATION_ID, ORDER_ID, ownerId, facilityId, event.getLines(),
        dispatchBy, 101, OCCURRED_AT))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void keepsWireEventTypesStableAndUnique() {
    List<String> eventTypes = List.of(
        OrderPlacedIntegrationEvent.EVENT_TYPE,
        OrderCancelledIntegrationEvent.EVENT_TYPE,
        OrderAllocatedIntegrationEvent.EVENT_TYPE,
        StockAvailabilityIncreasedIntegrationEvent.EVENT_TYPE,
        AllocationCommittedForFulfillmentIntegrationEvent.EVENT_TYPE);

    assertThat(eventTypes).containsExactly(
        "OrderPlacedIntegrationEvent",
        "OrderCancelledIntegrationEvent",
        "OrderAllocatedIntegrationEvent",
        "StockAvailabilityIncreasedIntegrationEvent",
        "AllocationCommittedForFulfillmentIntegrationEvent");
    assertThat(eventTypes).doesNotHaveDuplicates();
  }
}
