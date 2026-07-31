package com.flowzati.archone.common.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowzati.archone.allocation.application.event.PromisingEventTopics;
import com.flowzati.archone.allocation.application.event.BackorderCreatedIntegrationEvent;
import com.flowzati.archone.allocation.application.event.OrderAllocatedIntegrationEvent;
import com.flowzati.archone.allocation.application.event.translator.AllocationDomainEventTranslator;
import com.flowzati.archone.allocation.application.event.InventoryEventTopics;
import com.flowzati.archone.allocation.domain.event.BackorderWakeContinuationRequired;
import com.flowzati.archone.allocation.domain.event.OrderAllocationCompleted;
import com.flowzati.archone.ordering.application.event.OrderPlacedIntegrationEvent;
import com.flowzati.archone.ordering.application.event.OrderingEventTopics;
import com.flowzati.archone.ordering.application.event.translator.OrderingDomainEventTranslator;
import com.flowzati.archone.ordering.domain.event.LineSnapshot;
import com.flowzati.archone.allocation.domain.event.OrderBackorderRecorded;
import com.flowzati.archone.ordering.domain.event.OrderCancelled;
import com.flowzati.archone.ordering.domain.event.OrderPlaced;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class DomainEventTranslatorTest {

  private static final UUID OWNER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
  private static final UUID NODE_ID =
      UUID.fromString("00000000-0000-0000-0000-0000000000b1");
  private static final java.util.List<LineSnapshot> LINES =
      java.util.List.of(new LineSnapshot(1, "SKU-1", 3));

  private OrderPlaced placed(UUID orderId) {
    return new OrderPlaced(
        orderId, OWNER_ID, NODE_ID, "100", java.time.LocalDate.of(2026, 8, 1), LINES,
        occurredAt);
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
  @DisplayName("完成配置事件應譯為通知型的 Outbox 訊息，不攜帶預留明細")
  void shouldTranslateCompletedAllocationAsANotification() {
    OutboxRepo outboxRepo = mock(OutboxRepo.class);
    OutboxAppender appender = new OutboxAppender(outboxRepo, new ObjectMapper().findAndRegisterModules());
    UUID orderId = UUID.randomUUID();

    new AllocationDomainEventTranslator(appender).translate(
        new OrderAllocationCompleted(orderId, occurredAt));

    ArgumentCaptor<Outbox> outbox = ArgumentCaptor.forClass(Outbox.class);
    verify(outboxRepo).append(outbox.capture());
    assertThat(outbox.getValue().eventType())
        .isEqualTo(OrderAllocatedIntegrationEvent.class.getSimpleName());
    assertThat(outbox.getValue().route()).isEqualTo(PromisingEventTopics.ALLOCATION_EVENTS);
    assertThat(outbox.getValue().payload())
        .contains("\"orderId\":\"" + orderId + "\"");
    // 只帶識別與時間。預留明細不帶（它描述會被取消釋放的狀態，而取消事件在另一個 topic、
    // 沒有順序保證）；貨主與倉也不帶（沒有任何按它們過濾的消費端，加欄位是非破壞性的，
    // 真的需要時再加）。
    assertThat(outbox.getValue().payload())
        .doesNotContain("reservationId")
        .doesNotContain("batches")
        .doesNotContain("ownerId")
        .doesNotContain("nodeId");
  }

  @Test
  @DisplayName("partition-key-strategy=stock 時，下單事件以 (貨主, 倉) 當 partition key，aggregateId 仍是 orderId")
  void shouldUseSkuAsPartitionKeyButKeepOrderIdAsAggregateIdForPlaced() {
    OutboxRepo outboxRepo = mock(OutboxRepo.class);
    OutboxAppender appender = new OutboxAppender(outboxRepo, new ObjectMapper().findAndRegisterModules());
    UUID orderId = UUID.randomUUID();

    new OrderingDomainEventTranslator(appender, "stock")
        .translate(placed(orderId));

    ArgumentCaptor<Outbox> outbox = ArgumentCaptor.forClass(Outbox.class);
    verify(outboxRepo).append(outbox.capture());
    assertThat(outbox.getValue().partitionKey()).isEqualTo(OWNER_ID + "/" + NODE_ID);
    assertThat(outbox.getValue().aggregateId()).isEqualTo(orderId.toString());
  }

  @Test
  @DisplayName("預設策略下，取消事件的 aggregateId 與 partition key 皆為 orderId")
  void shouldUseOrderIdAsBothAggregateIdAndPartitionKeyForCancelledByDefault() {
    OutboxRepo outboxRepo = mock(OutboxRepo.class);
    OutboxAppender appender = new OutboxAppender(outboxRepo, new ObjectMapper().findAndRegisterModules());
    UUID orderId = UUID.randomUUID();

    new OrderingDomainEventTranslator(appender, "order-id")
        .translate(new OrderCancelled(orderId, OWNER_ID, NODE_ID, occurredAt));

    ArgumentCaptor<Outbox> outbox = ArgumentCaptor.forClass(Outbox.class);
    verify(outboxRepo).append(outbox.capture());
    assertThat(outbox.getValue().aggregateId()).isEqualTo(orderId.toString());
    assertThat(outbox.getValue().partitionKey()).isEqualTo(orderId.toString());
  }

  @Test
  @DisplayName("partition-key-strategy=stock 時，取消事件的 partition key 與下單一致")
  void shouldUseSkuAsPartitionKeyButKeepOrderIdAsAggregateIdForCancelled() {
    OutboxRepo outboxRepo = mock(OutboxRepo.class);
    OutboxAppender appender = new OutboxAppender(outboxRepo, new ObjectMapper().findAndRegisterModules());
    UUID orderId = UUID.randomUUID();

    new OrderingDomainEventTranslator(appender, "stock")
        .translate(new OrderCancelled(orderId, OWNER_ID, NODE_ID, occurredAt));

    ArgumentCaptor<Outbox> outbox = ArgumentCaptor.forClass(Outbox.class);
    verify(outboxRepo).append(outbox.capture());
    assertThat(outbox.getValue().partitionKey()).isEqualTo(OWNER_ID + "/" + NODE_ID);
    assertThat(outbox.getValue().aggregateId()).isEqualTo(orderId.toString());
  }

  @Test
  @DisplayName("配置完成事件以 orderId 當 partition key，不套用 SKU 分區策略")
  void shouldKeepOrderIdAsPartitionKeyForAllocatedOutcome() {
    OutboxRepo outboxRepo = mock(OutboxRepo.class);
    OutboxAppender appender = new OutboxAppender(outboxRepo, new ObjectMapper().findAndRegisterModules());
    UUID orderId = UUID.randomUUID();

    new AllocationDomainEventTranslator(appender).translate(
        new OrderAllocationCompleted(orderId, occurredAt));

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
        .translate(new OrderBackorderRecorded(orderId, occurredAt));

    ArgumentCaptor<Outbox> outbox = ArgumentCaptor.forClass(Outbox.class);
    verify(outboxRepo).append(outbox.capture());
    assertThat(outbox.getValue().eventType())
        .isEqualTo(BackorderCreatedIntegrationEvent.class.getSimpleName());
    assertThat(outbox.getValue().partitionKey()).isEqualTo(orderId.toString());
    assertThat(outbox.getValue().aggregateId()).isEqualTo(orderId.toString());
  }
  @Test
  @DisplayName("兩種策略下，跨多個 SKU 的訂單都必須翻譯得出來，且 key 不含 SKU")
  void translatesMultiSkuOrdersUnderEveryStrategy() {
    // **這支測試取代了三支舊的。**
    //
    // key 含 SKU 的那個版本與 ship-complete 根本衝突：跨 SKU 的訂單摺不出單一個 key，
    // 於是 requireSingleSku 會拋錯。當時有三支測試圍著那個限制轉——證明 order-id 策略
    // 不受影響（兩支）、證明 sku 策略會大聲失敗（一支）。
    //
    // key 改粗成 (貨主, 倉) 之後那個限制消失了：一張單不管跨幾個 SKU 都只屬於一個
    // (貨主, 倉)，兩種策略都算得出 key。所以要守的性質從「失敗要大聲」變成
    // **「不會失敗，而且 key 裡沒有 SKU」**——後半句是關鍵，SKU 一旦回到 key 裡，
    // R8 的多 SKU 訂單就又走不通了。
    List<LineSnapshot> twoSkus =
        List.of(new LineSnapshot(1, "SKU-1", 3), new LineSnapshot(2, "SKU-2", 5));

    for (String strategy : List.of("order-id", "stock")) {
      OutboxRepo outboxRepo = mock(OutboxRepo.class);
      OutboxAppender appender =
          new OutboxAppender(outboxRepo, new ObjectMapper().findAndRegisterModules());
      OrderingDomainEventTranslator translator =
          new OrderingDomainEventTranslator(appender, strategy);
      UUID orderId = UUID.randomUUID();

      translator.translate(new OrderPlaced(
          orderId, OWNER_ID, NODE_ID, "100", LocalDate.of(2026, 8, 1), twoSkus, occurredAt));
      translator.translate(new OrderCancelled(orderId, OWNER_ID, NODE_ID, occurredAt));

      ArgumentCaptor<Outbox> outbox = ArgumentCaptor.forClass(Outbox.class);
      verify(outboxRepo, org.mockito.Mockito.times(2)).append(outbox.capture());
      assertThat(outbox.getAllValues())
          .withFailMessage("策略 %s 下多 SKU 訂單應翻譯得出來", strategy)
          .hasSize(2)
          .allSatisfy(row -> {
            assertThat(row.partitionKey()).doesNotContain("SKU-1").doesNotContain("SKU-2");
            assertThat(row.payload()).doesNotContain("\"sku\"").doesNotContain("\"quantity\"");
          });
    }
  }

  @Test
  @DisplayName("續做喚醒以爭用群組當 key，不套用分區策略——它必須落回它要接續的那一輪的 partition")
  void shouldAlwaysKeyWakeContinuationByContentionGroup() {
    OutboxRepo outboxRepo = mock(OutboxRepo.class);
    OutboxAppender appender =
        new OutboxAppender(outboxRepo, new ObjectMapper().findAndRegisterModules());

    new AllocationDomainEventTranslator(appender).translate(
        new BackorderWakeContinuationRequired(OWNER_ID, NODE_ID, "SKU-1", occurredAt));

    ArgumentCaptor<Outbox> outbox = ArgumentCaptor.forClass(Outbox.class);
    verify(outboxRepo).append(outbox.capture());
    // 與配置結果事件相反：那些一律用 orderId，這一則一律用爭用群組。落到別的 partition
    // 就會與它要接續的那一輪並行，而 single writer 正是靠同 key 取得的。
    assertThat(outbox.getValue().partitionKey()).isEqualTo(OWNER_ID + "/" + NODE_ID);
    // topic 與補貨事件相同，兩者在 Kafka 層是同一條隊伍
    assertThat(outbox.getValue().route()).isEqualTo(InventoryEventTopics.STOCK_EVENTS);
    // aggregate 是庫存不是訂單——續做不屬於佇列裡的任何一張單
    assertThat(outbox.getValue().aggregateType()).isEqualTo(OutboxAggregateTypes.STOCK_POOL);
    // skuCode 必須進 payload：它決定要喚醒哪個 SKU 的佇列，與 key 只取 (貨主, 倉) 是兩件事
    assertThat(outbox.getValue().payload()).contains("\"sku\":\"SKU-1\"");
  }

  @Test
  @DisplayName("同碼 SKU 但不同貨主的事件應落在不同的 partition key")
  void shouldSeparatePartitionKeysForTheSameSkuUnderDifferentOwners() {
    OutboxRepo outboxRepo = mock(OutboxRepo.class);
    OutboxAppender appender =
        new OutboxAppender(outboxRepo, new ObjectMapper().findAndRegisterModules());
    UUID otherOwnerId = UUID.fromString("00000000-0000-0000-0000-0000000000a2");

    OrderingDomainEventTranslator translator = new OrderingDomainEventTranslator(appender, "stock");
    translator.translate(placed(UUID.randomUUID()));
    translator.translate(new OrderPlaced(
        UUID.randomUUID(), otherOwnerId, NODE_ID, "100",
        java.time.LocalDate.of(2026, 8, 1), LINES, occurredAt));

    ArgumentCaptor<Outbox> outbox = ArgumentCaptor.forClass(Outbox.class);
    verify(outboxRepo, org.mockito.Mockito.times(2)).append(outbox.capture());
    // 庫存分開之後，同碼不同貨主的訊息不再競爭同一列。仍擠進同一個 partition 只會讓它們
    // 互相排隊等一個根本不共用的鎖。
    assertThat(outbox.getAllValues().get(0).partitionKey())
        .isNotEqualTo(outbox.getAllValues().get(1).partitionKey());
  }

}
