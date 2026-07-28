package com.flowzati.archone.ordering.domain.model;

import com.flowzati.archone.ordering.domain.event.LineSnapshot;
import com.flowzati.archone.ordering.domain.event.OrderAllocated;
import com.flowzati.archone.ordering.domain.event.OrderBackordered;
import com.flowzati.archone.ordering.domain.event.OrderCancelled;
import com.flowzati.archone.ordering.domain.event.OrderPlaced;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderTest {

  private final UUID orderId = UUID.randomUUID();
  private final UUID ownerId = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private final Instant placedAt = Instant.parse("2026-07-23T00:00:00Z");

  @Test
  @DisplayName("建立訂單時應為 PENDING 並記錄下單 Domain Event")
  void shouldPlacePendingOrderAndRecordDomainEvent() {
    Order order = Order.place(orderId, ownerId, "EXT-1", delivery(), List.of(line(1, "SKU-1", 3)), placedAt);

    assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
    assertThat(order.getVersion()).isNull();
    assertThat(order.releaseDomainEvents()).containsExactly(
        new OrderPlaced(
            orderId,
            ownerId,
            "100",
            LocalDate.of(2026, 8, 1),
            List.of(new LineSnapshot(1, "SKU-1", 3)),
            placedAt));
    assertThat(order.releaseDomainEvents()).isEmpty();
  }

  @Test
  @DisplayName("訂單應持有貨主、上游單號與配送條件，並在查詢時原樣取回")
  void shouldRetainOwnerAndDeliveryTerms() {
    Order order = Order.place(orderId, ownerId, "EXT-1", delivery(), List.of(line(1, "SKU-1", 3)), placedAt);

    assertThat(order.getOwnerId()).isEqualTo(ownerId);
    assertThat(order.getExternalOrderNo()).isEqualTo("EXT-1");

    DeliveryTerms delivery = order.getDeliveryTerms();
    assertThat(delivery.shipToZone()).isEqualTo("100");
    assertThat(delivery.shipToAddress()).isEqualTo("台北市中正區重慶南路一段 122 號");
    assertThat(delivery.promisedDeliveryDate()).isEqualTo(LocalDate.of(2026, 8, 1));
    assertThat(delivery.requestedNodeId()).isNull();
  }

  @Test
  @DisplayName("PENDING 訂單應可配置")
  void shouldAllocatePendingOrder() {
    Instant allocatedAt = placedAt.plusSeconds(10);
    Order order = pendingOrder();

    order.markAllocated(allocatedAt);

    assertThat(order.getStatus()).isEqualTo(OrderStatus.ALLOCATED);
    assertThat(order.getAllocatedAt()).isEqualTo(allocatedAt);
    assertThat(order.releaseDomainEvents()).containsExactly(
        new OrderAllocated(orderId, ownerId, allocatedAt));
  }

  @Test
  @DisplayName("PENDING 訂單應可轉為欠單")
  void shouldBackorderPendingOrder() {
    Instant backorderedAt = placedAt.plusSeconds(10);
    Order order = pendingOrder();

    order.markBackOrdered(backorderedAt);

    assertThat(order.getStatus()).isEqualTo(OrderStatus.BACKORDERED);
    assertThat(order.getBackOrderedSince()).isEqualTo(backorderedAt);
    assertThat(order.releaseDomainEvents()).containsExactly(
        new OrderBackordered(
            orderId, ownerId, List.of(new LineSnapshot(1, "SKU-1", 3)), backorderedAt));
  }

  @Test
  @DisplayName("欠單配置成功時應保留欠單歷程")
  void shouldAllocateBackorderedOrderAndPreserveHistory() {
    Instant backorderedAt = placedAt.plusSeconds(10);
    Instant allocatedAt = placedAt.plusSeconds(20);
    Order order = pendingOrder();
    order.markBackOrdered(backorderedAt);
    order.releaseDomainEvents();

    order.markAllocated(allocatedAt);

    assertThat(order.getStatus()).isEqualTo(OrderStatus.ALLOCATED);
    assertThat(order.getBackOrderedSince()).isEqualTo(backorderedAt);
    assertThat(order.getAllocatedAt()).isEqualTo(allocatedAt);
  }

  @Test
  @DisplayName("訂單取消應只成功一次")
  void shouldCancelOrderOnlyOnce() {
    Instant cancelledAt = placedAt.plusSeconds(10);
    Order order = pendingOrder();

    assertThat(order.cancel(cancelledAt)).isTrue();
    assertThat(order.cancel(cancelledAt.plusSeconds(1))).isFalse();

    assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    assertThat(order.getCancelledAt()).isEqualTo(cancelledAt);
    assertThat(order.releaseDomainEvents()).containsExactly(
        new OrderCancelled(
            orderId, ownerId, List.of(new LineSnapshot(1, "SKU-1", 3)), cancelledAt));
  }

  @Test
  @DisplayName("已配置訂單應可取消")
  void shouldAllowAllocatedOrderToBeCancelled() {
    Order order = pendingOrder();
    order.markAllocated(placedAt.plusSeconds(10));
    order.releaseDomainEvents();

    order.cancel(placedAt.plusSeconds(20));

    assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    assertThat(order.getAllocatedAt()).isEqualTo(placedAt.plusSeconds(10));
  }

  @Test
  @DisplayName("不合法狀態轉換應被拒絕")
  void shouldRejectIllegalTransitions() {
    Order allocated = pendingOrder();
    allocated.markAllocated(placedAt.plusSeconds(1));

    assertThatThrownBy(() -> allocated.markBackOrdered(placedAt.plusSeconds(2)))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> allocated.markAllocated(placedAt.plusSeconds(2)))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("狀態轉換時間早於生命週期歷程時應被拒絕")
  void shouldRejectTransitionTimeBeforeLifecycleHistory() {
    Order order = pendingOrder();

    assertThatThrownBy(() -> order.markAllocated(placedAt.minusSeconds(1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> order.markBackOrdered(placedAt.minusSeconds(1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> order.cancel(placedAt.minusSeconds(1)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("建立訂單時應拒絕不合法資料")
  void shouldRejectInvalidOrderCreation() {
    List<OrderLine> lines = List.of(line(1, "SKU-1", 1));
    assertThatThrownBy(() -> Order.place(null, ownerId, "EXT-1", delivery(), lines, placedAt))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Order.place(orderId, null, "EXT-1", delivery(), lines, placedAt))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Order.place(orderId, ownerId, "EXT-1", delivery(), lines, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("訂單行必須屬於該訂單的貨主")
  void shouldRejectLinesBelongingToAnotherOwner() {
    UUID otherOwnerId = UUID.fromString("00000000-0000-0000-0000-000000000002");
    List<OrderLine> foreignLines =
        List.of(OrderLine.create(UUID.randomUUID(), 1, otherOwnerId, "SKU-1", 1));

    assertThatThrownBy(() -> Order.place(orderId, ownerId, "EXT-1", delivery(), foreignLines, placedAt))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("owner");
  }

  @Test
  @DisplayName("rehydrate 應還原訂單且不新增 Domain Event")
  void shouldRehydrateWithoutRecordingDomainEvents() {
    Instant backorderedAt = placedAt.plusSeconds(10);
    Instant allocatedAt = placedAt.plusSeconds(20);

    Order order = Order.rehydrate(
        orderId,
        ownerId,
        "EXT-1",
        delivery(),
        List.of(line(1, "SKU-1", 3)),
        OrderStatus.ALLOCATED,
        placedAt,
        allocatedAt,
        backorderedAt,
        null,
        4L
    );

    assertThat(order.getVersion()).isEqualTo(4L);
    assertThat(order.getBackOrderedSince()).isEqualTo(backorderedAt);
    assertThat(order.releaseDomainEvents()).isEmpty();
  }

  @Nested
  @DisplayName("收單政策：恰好一筆行")
  class SingleLineIntakePolicy {

    @Test
    @DisplayName("零筆或兩筆行的收單應被拒絕，且不留下任何資料")
    void rejectsIntakeThatIsNotExactlyOneLine() {
      assertThatThrownBy(() -> Order.place(orderId, ownerId, "EXT-1", delivery(), List.of(), placedAt))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("exactly one line");
      assertThatThrownBy(() -> Order.place(orderId, ownerId, "EXT-1", delivery(),
          List.of(line(1, "SKU-1", 1), line(2, "SKU-2", 1)), placedAt))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("exactly one line");
      assertThatThrownBy(() -> Order.place(orderId, ownerId, "EXT-1", delivery(), null, placedAt))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("rehydrate 不受此限制——它的職責是還原資料庫裡的任何東西")
    void rehydrationReconstructsMultipleLines() {
      Order order = twoLineOrder();

      assertThat(order.getLines()).extracting(OrderLine::getLineNo).containsExactly(1, 2);
      assertThat(order.getLines()).extracting(OrderLine::getSkuCode)
          .containsExactly("SKU-1", "SKU-2");
    }

    @Test
    @DisplayName("同一張單的行號不得重複")
    void rejectsDuplicateLineNumbers() {
      assertThatThrownBy(() -> Order.rehydrate(
          orderId,
          ownerId,
          "EXT-1",
          delivery(),
          List.of(line(1, "SKU-1", 1), line(1, "SKU-2", 1)),
          OrderStatus.PENDING,
          placedAt, null, null, null, null))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("line numbers must be unique");
    }
  }

  @Nested
  @DisplayName("getDemand：配貨讀取需求的唯一入口")
  class Demand {

    @Test
    @DisplayName("單行時應為單一項目，與改造前的行為相同")
    void aggregatesASingleLine() {
      assertThat(pendingOrder().getDemand()).isEqualTo(Map.of("SKU-1", 3));
    }

    @Test
    @DisplayName("同一個 SKU 的兩行應合併為一筆加總——配貨端看不到「行」")
    void mergesLinesOfTheSameSku() {
      Order order = Order.rehydrate(
          orderId,
          ownerId,
          "EXT-1",
          delivery(),
          List.of(line(1, "SKU-1", 5), line(2, "SKU-1", 5)),
          OrderStatus.PENDING,
          placedAt, null, null, null, null);

      assertThat(order.getDemand()).isEqualTo(Map.of("SKU-1", 10));
    }

    @Test
    @DisplayName("不同 SKU 的兩行應為兩筆")
    void keepsDistinctSkusApart() {
      assertThat(twoLineOrder().getDemand()).isEqualTo(Map.of("SKU-1", 3, "SKU-2", 7));
    }

    @Test
    @DisplayName("回傳的映射應不可變——配貨端不得改動訂單的需求")
    void returnsAnImmutableView() {
      Map<String, Integer> demand = pendingOrder().getDemand();

      assertThatThrownBy(() -> demand.put("SKU-9", 1))
          .isInstanceOf(UnsupportedOperationException.class);
    }
  }

  @Nested
  @DisplayName("requireSingleLine：把單行假設集中在一個名字上")
  class SingleLineAssumption {

    @Test
    @DisplayName("單行時應回傳該行")
    void returnsTheOnlyLine() {
      assertThat(pendingOrder().requireSingleLine().getSkuCode()).isEqualTo("SKU-1");
    }

    @Test
    @DisplayName("多行時應明確拋錯，而不是安靜取用第一行")
    void failsLoudlyWhenTheAssumptionBreaks() {
      assertThatThrownBy(() -> twoLineOrder().requireSingleLine())
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("single-line");
    }
  }

  @Nested
  @DisplayName("行的狀態與時間戳跟隨 header")
  class LinesMirrorHeader {

    @Test
    @DisplayName("兩行訂單轉為欠單後，header 與兩行帶同一個時間戳與狀態")
    void mirrorsBackorderAcrossAllLines() {
      Instant backorderedAt = placedAt.plusSeconds(10);
      Order order = twoLineOrder();

      order.markBackOrdered(backorderedAt);

      assertThat(order.getBackOrderedSince()).isEqualTo(backorderedAt);
      assertThat(order.getLines()).allSatisfy(line -> {
        assertThat(line.getStatus()).isEqualTo(OrderStatus.BACKORDERED);
        assertThat(line.getBackorderedSince()).isEqualTo(backorderedAt);
      });
    }

    @Test
    @DisplayName("配置後所有行一起成為 ALLOCATED，且不留下欠單時間")
    void mirrorsAllocationAcrossAllLines() {
      Order order = twoLineOrder();
      order.markBackOrdered(placedAt.plusSeconds(10));

      order.markAllocated(placedAt.plusSeconds(20));

      assertThat(order.getLines()).allSatisfy(line -> {
        assertThat(line.getStatus()).isEqualTo(OrderStatus.ALLOCATED);
        assertThat(line.getBackorderedSince()).isNull();
      });
    }

    @Test
    @DisplayName("取消後所有行一起成為 CANCELLED")
    void mirrorsCancellationAcrossAllLines() {
      Order order = twoLineOrder();

      order.cancel(placedAt.plusSeconds(10));

      assertThat(order.getLines())
          .allSatisfy(line -> assertThat(line.getStatus()).isEqualTo(OrderStatus.CANCELLED));
    }
  }

  /**
   * 一張已存在的 PENDING 訂單。走 {@code rehydrate} 而非 {@code place}——這些測試驗的是狀態
   * 轉換，不是收單，因此不該憑空產生一個必須立刻丟掉的 OrderPlaced 事件。
   */
  private Order pendingOrder() {
    return Order.rehydrate(
        orderId, ownerId, "EXT-1", delivery(), List.of(line(1, "SKU-1", 3)),
        OrderStatus.PENDING, placedAt, null, null, null, null);
  }

  /** 兩行訂單只能經由 rehydrate 造出——收單政策拒絕它，而讀取路徑必須撐得住。 */
  private Order twoLineOrder() {
    return Order.rehydrate(
        orderId,
        ownerId,
        "EXT-1",
        delivery(),
        List.of(line(1, "SKU-1", 3), line(2, "SKU-2", 7)),
        OrderStatus.PENDING,
        placedAt, null, null, null, null);
  }

  private DeliveryTerms delivery() {
    return new DeliveryTerms(
        "100",
        "台北市中正區重慶南路一段 122 號",
        LocalDate.of(2026, 8, 1),
        null);
  }

  private OrderLine line(int lineNo, String skuCode, int quantity) {
    return OrderLine.create(UUID.randomUUID(), lineNo, ownerId, skuCode, quantity);
  }
}
