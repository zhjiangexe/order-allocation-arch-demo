package com.flowzati.archone.promising.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowzati.archone.contracts.inventory.v1.StockAvailabilityIncreasedIntegrationEvent;
import com.flowzati.archone.contracts.ordering.v1.OrderCancelledIntegrationEvent;
import com.flowzati.archone.contracts.ordering.v1.OrderPlacedIntegrationEvent;
import com.flowzati.archone.contracts.promising.v1.BackorderCreatedIntegrationEvent;
import com.flowzati.archone.contracts.promising.v1.OrderAllocatedIntegrationEvent;
import com.flowzati.archone.messaging.events.IntegrationEventPublication;
import com.flowzati.archone.messaging.events.IntegrationEventPublisher;
import com.flowzati.archone.ordering.application.event.OrderingEventTopics;
import com.flowzati.archone.ordering.application.event.translator.OrderingDomainEventTranslator;
import com.flowzati.archone.ordering.domain.event.LineSnapshot;
import com.flowzati.archone.ordering.domain.event.OrderCancelled;
import com.flowzati.archone.ordering.domain.event.OrderPlaced;
import com.flowzati.archone.promising.domain.DomainEvent;
import com.flowzati.archone.stock.application.event.InventoryEventTopics;
import com.flowzati.archone.stock.application.event.PromisingEventTopics;
import com.flowzati.archone.stock.application.event.translator.AllocationDomainEventTranslator;
import com.flowzati.archone.stock.domain.event.OrderAllocationCompleted;
import com.flowzati.archone.stock.domain.event.OrderBackorderRecorded;
import com.flowzati.archone.stock.domain.event.StockAvailabilityIncreased;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DomainEventTranslatorTest {

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

    new OrderingDomainEventTranslator(publisher, "order-id").publish(placed(orderId));

    IntegrationEventPublication publication = publisher.onlyPublication();
    assertThat(publication.event()).isInstanceOf(OrderPlacedIntegrationEvent.class);
    assertThat(((OrderPlacedIntegrationEvent) publication.event()).getOrderId()).isEqualTo(orderId);
    assertThat(publication.aggregate().type()).isEqualTo(OutboxAggregateTypes.ORDER);
    assertThat(publication.aggregate().id()).isEqualTo(orderId.toString());
    assertThat(publication.target().destination()).isEqualTo(OrderingEventTopics.ORDER_EVENTS);
    assertThat(publication.target().partitionKey()).isEqualTo(orderId.toString());
    assertThat(publication.occurredAt()).isEqualTo(OCCURRED_AT);
  }

  @Test
  @DisplayName("stock 策略下，下單與取消使用相同的 (貨主, 倉) partition key")
  void shouldUseTheStockContentionKeyForPlacedAndCancelled() {
    RecordingPublisher publisher = new RecordingPublisher();
    OrderingDomainEventTranslator translator = new OrderingDomainEventTranslator(publisher, "stock");
    UUID orderId = UUID.randomUUID();

    translator.publish(placed(orderId));
    translator.publish(new OrderCancelled(orderId, OWNER_ID, FACILITY_ID, OCCURRED_AT));

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

      new OrderingDomainEventTranslator(publisher, strategy).publish(new OrderPlaced(
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
  @DisplayName("配置完成與缺貨事件皆以 orderId 路由到 allocation destination")
  void shouldTranslateAllocationOutcomes() {
    RecordingPublisher publisher = new RecordingPublisher();
    AllocationDomainEventTranslator translator = new AllocationDomainEventTranslator(publisher);
    UUID orderId = UUID.randomUUID();

    translator.publish(new OrderAllocationCompleted(orderId, OCCURRED_AT));
    translator.publish(new OrderBackorderRecorded(orderId, OCCURRED_AT));

    assertThat(publisher.publications)
        .extracting(publication -> publication.event().eventType())
        .containsExactly(
            OrderAllocatedIntegrationEvent.EVENT_TYPE,
            BackorderCreatedIntegrationEvent.EVENT_TYPE);
    assertThat(publisher.publications)
        .allSatisfy(publication -> {
          assertThat(publication.aggregate().type()).isEqualTo(OutboxAggregateTypes.ORDER);
          assertThat(publication.aggregate().id()).isEqualTo(orderId.toString());
          assertThat(publication.target().destination())
              .isEqualTo(PromisingEventTopics.ALLOCATION_EVENTS);
          assertThat(publication.target().partitionKey()).isEqualTo(orderId.toString());
          assertThat(publication.occurredAt()).isEqualTo(OCCURRED_AT);
        });
  }

  @Test
  @DisplayName("可用庫存增加應以庫存爭用群組路由到 inventory destination")
  void shouldTranslateStockAvailabilityIncrease() {
    RecordingPublisher publisher = new RecordingPublisher();
    UUID locationId = UUID.randomUUID();

    new AllocationDomainEventTranslator(publisher).publish(new StockAvailabilityIncreased(
        OWNER_ID, FACILITY_ID, locationId, "SKU-1", 12, OCCURRED_AT));

    IntegrationEventPublication publication = publisher.onlyPublication();
    assertThat(publication.event())
        .isInstanceOf(StockAvailabilityIncreasedIntegrationEvent.class);
    StockAvailabilityIncreasedIntegrationEvent event =
        (StockAvailabilityIncreasedIntegrationEvent) publication.event();
    assertThat(event.getLocationId()).isEqualTo(locationId);
    assertThat(event.getQuantity()).isEqualTo(12);
    assertThat(publication.aggregate().type()).isEqualTo(OutboxAggregateTypes.STOCK_POOL);
    assertThat(publication.target().destination()).isEqualTo(InventoryEventTopics.STOCK_EVENTS);
    assertThat(publication.target().partitionKey()).isEqualTo(OWNER_ID + "/" + FACILITY_ID);
  }

  @Test
  @DisplayName("同碼 SKU 但不同貨主的事件應落在不同 partition key")
  void shouldSeparateStockKeysForDifferentOwners() {
    RecordingPublisher publisher = new RecordingPublisher();
    OrderingDomainEventTranslator translator = new OrderingDomainEventTranslator(publisher, "stock");
    UUID otherOwnerId = UUID.fromString("00000000-0000-0000-0000-0000000000a2");

    translator.publish(placed(UUID.randomUUID()));
    translator.publish(new OrderPlaced(
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

  @Test
  @DisplayName("未知 Domain Event 必須明確失敗，不得被靜默遺漏")
  void shouldRejectUnsupportedDomainEvents() {
    DomainEvent unsupported = new DomainEvent() { };

    assertThatThrownBy(
        () -> new AllocationDomainEventTranslator(new RecordingPublisher()).publish(unsupported))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unsupported allocation domain event");
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
