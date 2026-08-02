package com.flowzati.archone.common.event;

import com.flowzati.archone.ordering.domain.event.LineSnapshot;
import com.flowzati.archone.stock.application.event.BackorderCreatedIntegrationEvent;
import com.flowzati.archone.stock.application.event.OrderAllocatedIntegrationEvent;
import com.flowzati.archone.stock.application.event.StockReplenishedIntegrationEvent;
import com.flowzati.archone.common.ddd.DomainEvent;
import com.flowzati.archone.common.integration.IntegrationEvent;
import com.flowzati.archone.ordering.domain.event.OrderPlaced;
import com.flowzati.archone.ordering.application.event.OrderCancelledIntegrationEvent;
import com.flowzati.archone.ordering.application.event.OrderPlacedIntegrationEvent;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventSeparationTest {

  private final UUID eventId = UUID.randomUUID();
  private final UUID orderId = UUID.randomUUID();
  private final UUID ownerId = UUID.randomUUID();
  private final UUID orderLineId = UUID.randomUUID();
  private final UUID stockPoolId = UUID.randomUUID();
  private final Instant occurredAt = Instant.parse("2026-07-23T00:00:00Z");

  private static final LocalDate IN_DATE = LocalDate.of(2026, 1, 5);
  private static final LocalDate EXPIRY_DATE = LocalDate.of(2026, 12, 31);

  @Test
  @DisplayName("Domain Event 應是沒有訊息識別的內部標記")
  void domainEventShouldBeAnInternalMarkerWithoutMessagingIdentity() {
    OrderPlaced event = new OrderPlaced(
        orderId,
        ownerId,
        UUID.fromString("00000000-0000-0000-0000-0000000000b1"),
        "100",
        java.time.LocalDate.of(2026, 8, 1),
        java.util.List.of(new LineSnapshot(1, "SKU-1", 3)),
        occurredAt);

    assertThat(event).isInstanceOf(DomainEvent.class);
    assertThat(event.getClass().getMethods())
        .noneMatch(method -> method.getName().equals("getEventId"));
  }

  @Test
  @DisplayName("Integration Event 應擁有不可變的事件識別")
  void integrationEventShouldOwnImmutableEventId() throws NoSuchFieldException {
    assertThat(Modifier.isFinal(
        IntegrationEvent.class.getDeclaredField("eventId").getModifiers())).isTrue();
    assertThat(IntegrationEvent.class.getMethods())
        .noneMatch(method -> method.getName().equals("setEventId"));
    assertThatThrownBy(() -> new StockReplenishedIntegrationEvent(null, com.flowzati.archone.testsupport.OrderFixtures.OWNER_ID, com.flowzati.archone.testsupport.OrderFixtures.NODE_ID, "SKU-1", IN_DATE, EXPIRY_DATE, 1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("Ordering Integration Event 應提供完整契約欄位")
  void shouldExposeCompleteOrderingIntegrationEventContracts() {
    OrderPlacedIntegrationEvent placed =
        new OrderPlacedIntegrationEvent(eventId, orderId, occurredAt);
    OrderCancelledIntegrationEvent cancelled =
        new OrderCancelledIntegrationEvent(eventId, orderId, occurredAt);

    // 訂單生命週期的對外事件只帶識別與時間。消費端拿 orderId 回頭讀整張單——它反正得讀,
    // 而 payload 抄一份需求內容只會多一個對不上的來源。
    assertThat(placed.getEventId()).isEqualTo(eventId);
    assertThat(placed.getOrderId()).isEqualTo(orderId);
    assertThat(placed.getReceivedAt()).isEqualTo(occurredAt);
    assertThat(cancelled.getOrderId()).isEqualTo(orderId);
    assertThat(cancelled.getCancelledAt()).isEqualTo(occurredAt);
  }

  @Test
  @DisplayName("Allocation Integration Event 應提供完整契約欄位")
  void shouldExposeCompleteAllocationIntegrationEventContracts() {
    OrderAllocatedIntegrationEvent allocated =
        new OrderAllocatedIntegrationEvent(eventId, orderId, occurredAt);
    BackorderCreatedIntegrationEvent backorder =
        new BackorderCreatedIntegrationEvent(eventId, orderId, occurredAt);
    StockReplenishedIntegrationEvent replenished = new StockReplenishedIntegrationEvent(eventId, com.flowzati.archone.testsupport.OrderFixtures.OWNER_ID, com.flowzati.archone.testsupport.OrderFixtures.NODE_ID, "SKU-1", IN_DATE, EXPIRY_DATE, 10);

    // 配貨結果事件是通知，不是狀態傳輸：帶得動的只有訂單識別與時間。要知道配到哪些批，
    // 回頭讀 stock_reservations——那份紀錄不會因為取消而與事件不一致。
    assertThat(allocated.getOrderId()).isEqualTo(orderId);
    assertThat(allocated.getAllocatedAt()).isEqualTo(occurredAt);
    assertThat(backorder.getOrderId()).isEqualTo(orderId);
    assertThat(backorder.getBackorderedSince()).isEqualTo(occurredAt);
    // 補貨事件是唯一該帶完整事實的一則:它來自系統外部,沒有本地聚合根可以重讀。
    assertThat(replenished.getQuantity()).isEqualTo(10);
  }

  @Test
  @DisplayName("Integration Event 應拒絕不合法 payload")
  void shouldRejectInvalidIntegrationEventPayloads() {
    assertThatThrownBy(() -> new OrderPlacedIntegrationEvent(eventId, null, occurredAt))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new StockReplenishedIntegrationEvent(eventId, com.flowzati.archone.testsupport.OrderFixtures.OWNER_ID, com.flowzati.archone.testsupport.OrderFixtures.NODE_ID, "SKU-1", IN_DATE, EXPIRY_DATE, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new OrderAllocatedIntegrationEvent(eventId, orderId, null))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
