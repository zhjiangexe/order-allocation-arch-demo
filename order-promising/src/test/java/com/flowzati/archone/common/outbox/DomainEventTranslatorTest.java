package com.flowzati.archone.common.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowzati.archone.allocation.application.event.PromisingEventTopics;
import com.flowzati.archone.allocation.application.event.BackorderCreatedIntegrationEvent;
import com.flowzati.archone.allocation.application.event.OrderAllocatedIntegrationEvent;
import com.flowzati.archone.allocation.application.event.translator.AllocationDomainEventTranslator;
import com.flowzati.archone.allocation.domain.event.OrderAllocationCompleted;
import com.flowzati.archone.ordering.application.event.OrderPlacedIntegrationEvent;
import com.flowzati.archone.ordering.application.event.OrderingEventTopics;
import com.flowzati.archone.ordering.application.event.translator.OrderingDomainEventTranslator;
import com.flowzati.archone.ordering.domain.event.LineSnapshot;
import com.flowzati.archone.ordering.domain.event.OrderBackordered;
import com.flowzati.archone.ordering.domain.event.OrderCancelled;
import com.flowzati.archone.ordering.domain.event.OrderPlaced;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class DomainEventTranslatorTest {

  private static final UUID OWNER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
  private static final java.util.List<LineSnapshot> LINES =
      java.util.List.of(new LineSnapshot(1, "SKU-1", 3));

  private OrderPlaced placed(UUID orderId) {
    return new OrderPlaced(
        orderId, OWNER_ID, "100", java.time.LocalDate.of(2026, 8, 1), LINES, occurredAt);
  }

  private final Instant occurredAt = Instant.parse("2026-07-24T10:00:00Z");

  @Test
  @DisplayName("下單 Domain Event 應翻譯並附加至 Outbox")
  void shouldTranslateOrderPlacedAndAppendOutboxRow() throws Exception {
    OutboxRepo outboxRepo = mock(OutboxRepo.class);
    OutboxAppender appender = new OutboxAppender(outboxRepo, new ObjectMapper().findAndRegisterModules());
    UUID orderId = UUID.randomUUID();

    new OrderingDomainEventTranslator(appender, "order-id")
        .translate(placed(orderId));

    ArgumentCaptor<Outbox> outbox = ArgumentCaptor.forClass(Outbox.class);
    verify(outboxRepo).append(outbox.capture());
    assertThat(outbox.getValue()).satisfies(row -> {
      assertThat(row.aggregateType()).isEqualTo(OutboxAggregateTypes.ORDER);
      assertThat(row.aggregateId()).isEqualTo(orderId.toString());
      assertThat(row.eventType()).isEqualTo(OrderPlacedIntegrationEvent.class.getSimpleName());
      assertThat(row.route()).isEqualTo(OrderingEventTopics.ORDER_EVENTS);
      assertThat(row.partitionKey()).isEqualTo(orderId.toString());
      assertThat(row.occurredAt()).isEqualTo(occurredAt);
      assertThat(row.payload()).contains("\"orderId\":\"" + orderId + "\"");
    });
  }

  @Test
  @DisplayName("完成配置事件應帶著 Reservation 明細翻譯為 Outbox 訊息")
  void shouldTranslateCompletedAllocationWithReservationDetails() {
    OutboxRepo outboxRepo = mock(OutboxRepo.class);
    OutboxAppender appender = new OutboxAppender(outboxRepo, new ObjectMapper().findAndRegisterModules());
    UUID orderId = UUID.randomUUID();
    UUID reservationId = UUID.randomUUID();

    new AllocationDomainEventTranslator(appender).translate(new OrderAllocationCompleted(
        orderId, reservationId, "SKU-1", 3, occurredAt));

    ArgumentCaptor<Outbox> outbox = ArgumentCaptor.forClass(Outbox.class);
    verify(outboxRepo).append(outbox.capture());
    assertThat(outbox.getValue().eventType())
        .isEqualTo(OrderAllocatedIntegrationEvent.class.getSimpleName());
    assertThat(outbox.getValue().route()).isEqualTo(PromisingEventTopics.ALLOCATION_EVENTS);
    assertThat(outbox.getValue().payload())
        .contains("\"reservationId\":\"" + reservationId + "\"");
  }

  @Test
  @DisplayName("partition-key-strategy=sku 時，下單事件以 SKU 當 partition key，aggregateId 仍是 orderId")
  void shouldUseSkuAsPartitionKeyButKeepOrderIdAsAggregateIdForPlaced() {
    OutboxRepo outboxRepo = mock(OutboxRepo.class);
    OutboxAppender appender = new OutboxAppender(outboxRepo, new ObjectMapper().findAndRegisterModules());
    UUID orderId = UUID.randomUUID();

    new OrderingDomainEventTranslator(appender, "sku")
        .translate(placed(orderId));

    ArgumentCaptor<Outbox> outbox = ArgumentCaptor.forClass(Outbox.class);
    verify(outboxRepo).append(outbox.capture());
    assertThat(outbox.getValue().partitionKey()).isEqualTo("SKU-1");
    assertThat(outbox.getValue().aggregateId()).isEqualTo(orderId.toString());
  }

  @Test
  @DisplayName("預設策略下，取消事件的 aggregateId 與 partition key 皆為 orderId")
  void shouldUseOrderIdAsBothAggregateIdAndPartitionKeyForCancelledByDefault() {
    OutboxRepo outboxRepo = mock(OutboxRepo.class);
    OutboxAppender appender = new OutboxAppender(outboxRepo, new ObjectMapper().findAndRegisterModules());
    UUID orderId = UUID.randomUUID();

    new OrderingDomainEventTranslator(appender, "order-id")
        .translate(new OrderCancelled(orderId, OWNER_ID, LINES, occurredAt));

    ArgumentCaptor<Outbox> outbox = ArgumentCaptor.forClass(Outbox.class);
    verify(outboxRepo).append(outbox.capture());
    assertThat(outbox.getValue().aggregateId()).isEqualTo(orderId.toString());
    assertThat(outbox.getValue().partitionKey()).isEqualTo(orderId.toString());
  }

  @Test
  @DisplayName("partition-key-strategy=sku 時，取消事件以 SKU 當 partition key，aggregateId 仍是 orderId")
  void shouldUseSkuAsPartitionKeyButKeepOrderIdAsAggregateIdForCancelled() {
    OutboxRepo outboxRepo = mock(OutboxRepo.class);
    OutboxAppender appender = new OutboxAppender(outboxRepo, new ObjectMapper().findAndRegisterModules());
    UUID orderId = UUID.randomUUID();

    new OrderingDomainEventTranslator(appender, "sku")
        .translate(new OrderCancelled(orderId, OWNER_ID, LINES, occurredAt));

    ArgumentCaptor<Outbox> outbox = ArgumentCaptor.forClass(Outbox.class);
    verify(outboxRepo).append(outbox.capture());
    assertThat(outbox.getValue().partitionKey()).isEqualTo("SKU-1");
    assertThat(outbox.getValue().aggregateId()).isEqualTo(orderId.toString());
  }

  @Test
  @DisplayName("配置完成事件以 orderId 當 partition key，不套用 SKU 分區策略")
  void shouldKeepOrderIdAsPartitionKeyForAllocatedOutcome() {
    OutboxRepo outboxRepo = mock(OutboxRepo.class);
    OutboxAppender appender = new OutboxAppender(outboxRepo, new ObjectMapper().findAndRegisterModules());
    UUID orderId = UUID.randomUUID();

    new AllocationDomainEventTranslator(appender).translate(new OrderAllocationCompleted(
        orderId, UUID.randomUUID(), "SKU-1", 3, occurredAt));

    ArgumentCaptor<Outbox> outbox = ArgumentCaptor.forClass(Outbox.class);
    verify(outboxRepo).append(outbox.capture());
    assertThat(outbox.getValue().partitionKey()).isEqualTo(orderId.toString());
    assertThat(outbox.getValue().aggregateId()).isEqualTo(orderId.toString());
  }

  @Test
  @DisplayName("缺貨事件以 orderId 當 partition key，不套用 SKU 分區策略")
  void shouldKeepOrderIdAsPartitionKeyForBackorderOutcome() {
    OutboxRepo outboxRepo = mock(OutboxRepo.class);
    OutboxAppender appender = new OutboxAppender(outboxRepo, new ObjectMapper().findAndRegisterModules());
    UUID orderId = UUID.randomUUID();

    new AllocationDomainEventTranslator(appender)
        .translate(new OrderBackordered(orderId, OWNER_ID, LINES, occurredAt));

    ArgumentCaptor<Outbox> outbox = ArgumentCaptor.forClass(Outbox.class);
    verify(outboxRepo).append(outbox.capture());
    assertThat(outbox.getValue().eventType())
        .isEqualTo(BackorderCreatedIntegrationEvent.class.getSimpleName());
    assertThat(outbox.getValue().partitionKey()).isEqualTo(orderId.toString());
    assertThat(outbox.getValue().aggregateId()).isEqualTo(orderId.toString());
  }
  @Test
  @DisplayName("order-id 策略下，跨多個 SKU 的訂單仍能翻譯——那個策略不需要 SKU")
  void orderIdStrategyDoesNotNeedTheSkuAtAll() {
    OutboxRepo outboxRepo = mock(OutboxRepo.class);
    OutboxAppender appender =
        new OutboxAppender(outboxRepo, new ObjectMapper().findAndRegisterModules());
    UUID orderId = UUID.randomUUID();

    // 這是 R8「策略退場」那條出路的前提:退回 order-id 之後,多 SKU 的訂單必須真的跑得動。
    // SKU 若在呼叫端就先求值,requireSingleSku 會在這裡拋錯,而那個策略根本用不到 SKU。
    new OrderingDomainEventTranslator(appender, "order-id")
        .translate(new OrderCancelled(
            orderId,
            OWNER_ID,
            java.util.List.of(
                new LineSnapshot(1, "SKU-1", 3),
                new LineSnapshot(2, "SKU-2", 5)),
            occurredAt));

    ArgumentCaptor<Outbox> outbox = ArgumentCaptor.forClass(Outbox.class);
    verify(outboxRepo).append(outbox.capture());
    assertThat(outbox.getValue().partitionKey()).isEqualTo(orderId.toString());
  }
}