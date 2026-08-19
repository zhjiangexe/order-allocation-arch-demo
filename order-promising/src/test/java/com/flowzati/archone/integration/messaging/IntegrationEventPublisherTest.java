package com.flowzati.archone.integration.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowzati.archone.contracts.fulfillment.v1.FulfillmentAggregateTypes;
import com.flowzati.archone.contracts.inventory.v1.StockAvailabilityIncreasedIntegrationEvent;
import com.flowzati.archone.contracts.inventory.v1.InventoryAggregateTypes;
import com.flowzati.archone.contracts.fulfillment.v1.AllocationCommittedForFulfillmentIntegrationEvent;
import com.flowzati.archone.contracts.ordering.v1.OrderingAggregateTypes;
import com.flowzati.archone.contracts.ordering.v1.OrderCancelledIntegrationEvent;
import com.flowzati.archone.contracts.ordering.v1.OrderPlacedIntegrationEvent;
import com.flowzati.archone.contracts.promising.v1.OrderAllocatedIntegrationEvent;
import com.flowzati.archone.messaging.events.IntegrationEventPublication;
import com.flowzati.archone.messaging.events.IntegrationEventPublisher;
import com.flowzati.archone.contracts.ordering.v1.OrderingChannels;
import com.flowzati.archone.ordering.infrastructure.messaging.producer.OrderingIntegrationEventPublisher;
import com.flowzati.archone.ordering.domain.event.LineSnapshot;
import com.flowzati.archone.ordering.domain.event.OrderCancelled;
import com.flowzati.archone.ordering.domain.event.OrderPlaced;
import com.flowzati.archone.contracts.inventory.v1.InventoryChannels;
import com.flowzati.archone.contracts.fulfillment.v1.FulfillmentChannels;
import com.flowzati.archone.contracts.promising.v1.AllocationChannels;
import com.flowzati.archone.stock.allocation.infrastructure.messaging.producer.AllocationIntegrationEventPublisher;
import com.flowzati.archone.stock.inventory.infrastructure.messaging.producer.InventoryIntegrationEventPublisher;
import com.flowzati.archone.stock.allocation.domain.event.OrderAllocationCompleted;
import com.flowzati.archone.stock.inventory.domain.event.StockAvailabilityIncreased;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IntegrationEventPublisherTest {

  private static final UUID OWNER_ID =
      UUID.fromString("00000000-0000-0000-0000-0000000000a1");
  private static final UUID FACILITY_ID =
      UUID.fromString("00000000-0000-0000-0000-0000000000b1");
  private static final List<LineSnapshot> LINES =
      List.of(new LineSnapshot(1, "SKU-1", 3));
  private static final Instant OCCURRED_AT = Instant.parse("2026-07-24T10:00:00Z");

  @Test
  @DisplayName("下單 Domain Event 應直接翻譯成 transactional Integration Event publication")
  void shouldTranslateOrderPlaced() {
    RecordingPublisher publisher = new RecordingPublisher();
    UUID orderId = UUID.randomUUID();

    new OrderingIntegrationEventPublisher(publisher, "order-id").publish(placed(orderId));

    IntegrationEventPublication publication = publisher.onlyPublication();
    assertThat(publication.event()).isInstanceOf(OrderPlacedIntegrationEvent.class);
    assertThat(((OrderPlacedIntegrationEvent) publication.event()).getOrderId()).isEqualTo(orderId);
    assertThat(publication.aggregate().type()).isEqualTo(OrderingAggregateTypes.ORDER);
    assertThat(publication.aggregate().id()).isEqualTo(orderId.toString());
    assertThat(publication.target().destination()).isEqualTo(OrderingChannels.ORDER_EVENTS);
    assertThat(publication.target().partitionKey()).isEqualTo(orderId.toString());
    assertThat(publication.occurredAt()).isEqualTo(OCCURRED_AT);
  }

  @Test
  @DisplayName("stock 策略下，下單與取消使用相同的 (貨主, 倉) partition key")
  void shouldUseTheStockContentionKeyForPlacedAndCancelled() {
    RecordingPublisher publisher = new RecordingPublisher();
    OrderingIntegrationEventPublisher publisherAdapter =
        new OrderingIntegrationEventPublisher(publisher, "stock");
    UUID orderId = UUID.randomUUID();

    publisherAdapter.publish(placed(orderId));
    publisherAdapter.publish(new OrderCancelled(orderId, OWNER_ID, FACILITY_ID, OCCURRED_AT));

    assertThat(publisher.publications)
        .extracting(publication -> publication.target().partitionKey())
        .containsExactly(
            OWNER_ID + "/" + FACILITY_ID,
            OWNER_ID + "/" + FACILITY_ID);
    assertThat(publisher.publications)
        .extracting(publication -> publication.aggregate().id())
        .containsOnly(orderId.toString());
    assertThat(publisher.publications)
        .extracting(publication -> publication.event().eventType())
        .containsExactly(
            OrderPlacedIntegrationEvent.EVENT_TYPE,
            OrderCancelledIntegrationEvent.EVENT_TYPE);
  }

  @Test
  @DisplayName("兩種策略都支援多 SKU 訂單，且 partition key 不含 SKU")
  void shouldTranslateMultiSkuOrdersUnderEveryStrategy() {
    List<LineSnapshot> twoSkus =
        List.of(new LineSnapshot(1, "SKU-1", 3), new LineSnapshot(2, "SKU-2", 5));

    for (String strategy : List.of("order-id", "stock")) {
      RecordingPublisher publisher = new RecordingPublisher();
      UUID orderId = UUID.randomUUID();

      new OrderingIntegrationEventPublisher(publisher, strategy).publish(new OrderPlaced(
          orderId,
          OWNER_ID,
          FACILITY_ID,
          "100",
          LocalDate.of(2026, 8, 1),
          twoSkus,
          OCCURRED_AT));

      assertThat(publisher.onlyPublication().target().partitionKey())
          .doesNotContain("SKU-1")
          .doesNotContain("SKU-2");
    }
  }

  @Test
  @DisplayName("配置完成同時產生 lifecycle notification 與完整 fulfillment handoff")
  void shouldTranslateAllocationOutcomes() {
    RecordingPublisher publisher = new RecordingPublisher();
    AllocationIntegrationEventPublisher publisherAdapter =
        new AllocationIntegrationEventPublisher(publisher);
    UUID orderId = UUID.randomUUID();
    UUID allocationId = UUID.randomUUID();
    UUID orderLineId = UUID.randomUUID();
    UUID moveId = UUID.randomUUID();
    UUID locationId = UUID.randomUUID();

    publisherAdapter.publish(new OrderAllocationCompleted(
        allocationId,
        orderId,
        OWNER_ID,
        FACILITY_ID,
        List.of(new OrderAllocationCompleted.AllocationLine(
            orderLineId, moveId, "SKU-1", locationId, 3)),
        OCCURRED_AT.plusSeconds(3600),
        80,
        OCCURRED_AT));
    assertThat(publisher.publications)
        .extracting(publication -> publication.event().eventType())
        .containsExactly(
            OrderAllocatedIntegrationEvent.EVENT_TYPE,
            AllocationCommittedForFulfillmentIntegrationEvent.EVENT_TYPE);
    assertThat(publisher.publications.get(0).aggregate().type())
        .isEqualTo(OrderingAggregateTypes.ORDER);
    assertThat(publisher.publications.get(0).target().destination())
        .isEqualTo(AllocationChannels.ALLOCATION_EVENTS);
    IntegrationEventPublication fulfillmentPublication = publisher.publications.get(1);
    assertThat(fulfillmentPublication.aggregate().type())
        .isEqualTo(FulfillmentAggregateTypes.STOCK_PICKING);
    assertThat(fulfillmentPublication.aggregate().id()).isEqualTo(allocationId.toString());
    assertThat(fulfillmentPublication.target().destination())
        .isEqualTo(FulfillmentChannels.FULFILLMENT_HANDOFFS);
    var fulfillment = (AllocationCommittedForFulfillmentIntegrationEvent)
        fulfillmentPublication.event();
    assertThat(fulfillment.getOrderId()).isEqualTo(orderId);
    assertThat(fulfillment.getLines()).singleElement().satisfies(line -> {
      assertThat(line.moveId()).isEqualTo(moveId);
      assertThat(line.sourceLocationId()).isEqualTo(locationId);
      assertThat(line.quantity()).isEqualTo(3);
    });
    assertThat(publisher.publications)
        .allSatisfy(publication -> {
          assertThat(publication.target().partitionKey()).isEqualTo(orderId.toString());
          assertThat(publication.occurredAt()).isEqualTo(OCCURRED_AT);
        });
  }

  @Test
  @DisplayName("可用庫存增加應以庫存爭用群組路由到 inventory destination")
  void shouldTranslateStockAvailabilityIncrease() {
    RecordingPublisher publisher = new RecordingPublisher();
    UUID locationId = UUID.randomUUID();

    new InventoryIntegrationEventPublisher(publisher).publish(new StockAvailabilityIncreased(
        OWNER_ID, FACILITY_ID, locationId, "SKU-1", 12, OCCURRED_AT));

    IntegrationEventPublication publication = publisher.onlyPublication();
    assertThat(publication.event())
        .isInstanceOf(StockAvailabilityIncreasedIntegrationEvent.class);
    StockAvailabilityIncreasedIntegrationEvent event =
        (StockAvailabilityIncreasedIntegrationEvent) publication.event();
    assertThat(event.getLocationId()).isEqualTo(locationId);
    assertThat(event.getQuantity()).isEqualTo(12);
    assertThat(publication.aggregate().type()).isEqualTo(InventoryAggregateTypes.STOCK_POOL);
    assertThat(publication.target().destination()).isEqualTo(InventoryChannels.STOCK_EVENTS);
    assertThat(publication.target().partitionKey()).isEqualTo(OWNER_ID + "/" + FACILITY_ID);
  }

  @Test
  @DisplayName("同碼 SKU 但不同貨主的事件應落在不同 partition key")
  void shouldSeparateStockKeysForDifferentOwners() {
    RecordingPublisher publisher = new RecordingPublisher();
    OrderingIntegrationEventPublisher publisherAdapter =
        new OrderingIntegrationEventPublisher(publisher, "stock");
    UUID otherOwnerId = UUID.fromString("00000000-0000-0000-0000-0000000000a2");

    publisherAdapter.publish(placed(UUID.randomUUID()));
    publisherAdapter.publish(new OrderPlaced(
        UUID.randomUUID(),
        otherOwnerId,
        FACILITY_ID,
        "100",
        LocalDate.of(2026, 8, 1),
        LINES,
        OCCURRED_AT));

    assertThat(publisher.publications.get(0).target().partitionKey())
        .isNotEqualTo(publisher.publications.get(1).target().partitionKey());
  }

  private static OrderPlaced placed(UUID orderId) {
    return new OrderPlaced(
        orderId,
        OWNER_ID,
        FACILITY_ID,
        "100",
        LocalDate.of(2026, 8, 1),
        LINES,
        OCCURRED_AT);
  }

  private static final class RecordingPublisher implements IntegrationEventPublisher {
    private final List<IntegrationEventPublication> publications = new ArrayList<>();

    @Override
    public void publish(IntegrationEventPublication publication) {
      publications.add(publication);
    }

    private IntegrationEventPublication onlyPublication() {
      assertThat(publications).hasSize(1);
      return publications.getFirst();
    }
  }
}
