package com.flowzati.archone.allocation.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowzati.archone.allocation.domain.model.StockPool;
import com.flowzati.archone.allocation.domain.service.selector.AllocationSelector;
import com.flowzati.archone.ordering.domain.model.Order;
import com.flowzati.archone.ordering.domain.model.OrderStatus;
import com.flowzati.archone.testsupport.OrderFixtures;
import com.flowzati.archone.ordering.domain.model.OrderLine;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("嚴格 FIFO allocation domain service")
class AllocationServiceTest {

  private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z");

  private AllocationService allocationService;

  @BeforeEach
  void setUp() {
    allocationService = new AllocationService();
  }

  @Test
  @DisplayName("ATP 充足時應完整預留並將 Order 標記為 ALLOCATED")
  void allocatesCompleteOrderWhenAtpIsSufficient() {
    StockPool stockPool = stockPool(10, 2);
    Order order = pendingOrder(5);

    AllocationOutcome outcome = allocationService.allocate(order, stockPool, NOW);

    assertThat(outcome).isEqualTo(AllocationOutcome.ALLOCATED);
    assertThat(order.getStatus()).isEqualTo(OrderStatus.ALLOCATED);
    assertThat(order.getAllocatedAt()).isEqualTo(NOW);
    assertThat(stockPool.getOnHandQuantity()).isEqualTo(10);
    assertThat(stockPool.getReservedQuantity()).isEqualTo(7);
    assertThat(stockPool.availableToPromise()).isEqualTo(3);
  }

  @Test
  @DisplayName("ATP 不足時應回傳業務結果且不做部分預留，由 application 決定 backorder")
  void returnsInsufficientAtpWithoutPartialReservation() {
    StockPool stockPool = stockPool(5, 2);
    Order order = pendingOrder(4);

    AllocationOutcome outcome = allocationService.allocate(order, stockPool, NOW);

    assertThat(outcome).isEqualTo(AllocationOutcome.INSUFFICIENT_ATP);
    assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
    assertThat(order.getAllocatedAt()).isNull();
    assertThat(stockPool.getReservedQuantity()).isEqualTo(2);
    assertThat(stockPool.availableToPromise()).isEqualTo(3);
  }

  @Test
  @DisplayName("reservation quantity 剛好等於 ATP 時仍應完整成功")
  void allocatesWhenQuantityExactlyMatchesAtp() {
    StockPool stockPool = stockPool(5, 2);
    Order order = pendingOrder(3);

    assertThat(allocationService.allocate(order, stockPool, NOW))
        .isEqualTo(AllocationOutcome.ALLOCATED);
    assertThat(stockPool.getReservedQuantity()).isEqualTo(5);
    assertThat(stockPool.availableToPromise()).isZero();
  }

  @Test
  @DisplayName("FIFO 首單無法完整預留時應立即停止，不可跳過去分配較小後單")
  void stopsAtHeadOfLineWhenFirstOrderCannotBeFullyReserved() {
    StockPool stockPool = stockPool(3, 0);
    Order first = backorderedOrder(4, 3);
    Order smallerLaterOrder = backorderedOrder(2, 2);

    List<Order> allocatedOrders = allocationService.allocateBackorders(
        List.of(first, smallerLaterOrder),
        stockPool,
        NOW
    );

    assertThat(allocatedOrders).isEmpty();
    assertThat(first.getStatus()).isEqualTo(OrderStatus.BACKORDERED);
    assertThat(smallerLaterOrder.getStatus()).isEqualTo(OrderStatus.BACKORDERED);
    assertThat(stockPool.getReservedQuantity()).isZero();
  }

  @Test
  @DisplayName("分配前單後遇到不足應停止，後續可滿足的小單也不可跳單")
  void preservesAllocatedPrefixAndStopsAfterFirstInsufficientOrder() {
    StockPool stockPool = stockPool(5, 0);
    Order first = backorderedOrder(3, 3);
    Order blocked = backorderedOrder(4, 2);
    Order smallerLaterOrder = backorderedOrder(1, 1);

    List<Order> allocatedOrders = allocationService.allocateBackorders(
        List.of(first, blocked, smallerLaterOrder),
        stockPool,
        NOW
    );

    assertThat(allocatedOrders).containsExactly(first);
    assertThat(first.getStatus()).isEqualTo(OrderStatus.ALLOCATED);
    assertThat(blocked.getStatus()).isEqualTo(OrderStatus.BACKORDERED);
    assertThat(smallerLaterOrder.getStatus()).isEqualTo(OrderStatus.BACKORDERED);
    assertThat(stockPool.getReservedQuantity()).isEqualTo(3);
    assertThat(stockPool.availableToPromise()).isEqualTo(2);
  }

  @Test
  @DisplayName("ATP 足以供應所有 FIFO orders 時應回傳完整 allocated prefix 且沒有阻塞單")
  void allocatesAllOrdersWhenAtpIsSufficient() {
    StockPool stockPool = stockPool(10, 0);
    Order first = backorderedOrder(3, 2);
    Order second = backorderedOrder(5, 1);

    List<Order> allocatedOrders = allocationService.allocateBackorders(
        List.of(first, second),
        stockPool,
        NOW
    );

    assertThat(allocatedOrders).containsExactly(first, second);
    assertThat(stockPool.getReservedQuantity()).isEqualTo(8);
  }

  @Test
  @DisplayName("Maximize policy 應跳過大單並配置最多完整訂單")
  void maximizesNumberOfFulfilledOrdersWhenConfigured() {
    AllocationService maximizingService =
        new AllocationService(AllocationSelector.maximizeFulfilledOrders());
    StockPool stockPool = stockPool(5, 0);
    Order largeFirst = backorderedOrder(6, 3);
    Order second = backorderedOrder(2, 2);
    Order third = backorderedOrder(3, 1);

    List<Order> allocatedOrders = maximizingService.allocateBackorders(
        List.of(largeFirst, second, third),
        stockPool,
        NOW
    );

    assertThat(allocatedOrders).containsExactly(second, third);
    assertThat(largeFirst.getStatus()).isEqualTo(OrderStatus.BACKORDERED);
    assertThat(second.getStatus()).isEqualTo(OrderStatus.ALLOCATED);
    assertThat(third.getStatus()).isEqualTo(OrderStatus.ALLOCATED);
    assertThat(stockPool.availableToPromise()).isZero();
  }

  @Test
  @DisplayName("Order 與 StockPool SKU 不一致時應在修改 aggregate 前拒絕")
  void rejectsSkuMismatchBeforeMutation() {
    StockPool stockPool = stockPool(10, 0);
    Order order = OrderFixtures.pendingOrder(UUID.randomUUID(), "OTHER-SKU", 3, NOW.minusSeconds(1));
    order.releaseDomainEvents();

    assertThatThrownBy(() -> allocationService.allocate(order, stockPool, NOW))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Order and stock pool SKU must match");

    assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
    assertThat(stockPool.getReservedQuantity()).isZero();
  }

  @Test
  @DisplayName("同一個 SKU 的兩行應以加總判斷，ATP 不足時整單不配、零筆預留")
  void treatsTwoLinesOfTheSameSkuAsOneBasket() {
    StockPool stockPool = stockPool(5, 0);
    // 兩行各要 5，加總 10；可承諾量只有 5。逐行獨立配貨的實作會讓第一行配到 5——
    // 那正是這支測試要擋的：為一張出不去的單鎖住庫存。
    Order order = sameSkuTwoLineOrder(5, 5);

    AllocationOutcome outcome = allocationService.allocate(order, stockPool, NOW);

    assertThat(outcome).isEqualTo(AllocationOutcome.INSUFFICIENT_ATP);
    assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
    assertThat(stockPool.getReservedQuantity()).isZero();
  }

  @Test
  @DisplayName("同一個 SKU 的兩行在 ATP 足夠時應一起配到，預留量為加總")
  void allocatesTwoLinesOfTheSameSkuTogether() {
    StockPool stockPool = stockPool(10, 0);
    Order order = sameSkuTwoLineOrder(5, 5);

    AllocationOutcome outcome = allocationService.allocate(order, stockPool, NOW);

    // 收單入口目前擋著多行，但配貨本身已經處理得了——擋著它的只有那一個檢查。
    assertThat(outcome).isEqualTo(AllocationOutcome.ALLOCATED);
    assertThat(order.getStatus()).isEqualTo(OrderStatus.ALLOCATED);
    assertThat(stockPool.getReservedQuantity()).isEqualTo(10);
    assertThat(order.getLines())
        .allSatisfy(line -> assertThat(line.getStatus()).isEqualTo(OrderStatus.ALLOCATED));
  }

  @Test
  @DisplayName("跨多個 SKU 的訂單不得被單一 StockPool 配貨——整籃裡有這個池滿足不了的東西")
  void rejectsAnOrderWhoseDemandSpansMoreThanThisPool() {
    StockPool stockPool = stockPool(100, 0);
    // SKU-1 這一行這個池滿足得了，SKU-2 那一行它完全不認識。
    Order order = twoSkuOrder();

    assertThatThrownBy(() -> allocationService.allocate(order, stockPool, NOW))
        .isInstanceOf(RuntimeException.class);

    assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
    assertThat(stockPool.getReservedQuantity()).isZero();
  }

  @Test
  @DisplayName("補貨喚醒也不得把跨多個 SKU 的訂單整張配掉")
  void rejectsMultiSkuOrdersWhenWakingBackorders() {
    StockPool stockPool = stockPool(100, 0);
    Order order = twoSkuOrder();
    order.markBackOrdered(NOW.minusSeconds(3));
    order.releaseDomainEvents();

    assertThatThrownBy(() -> allocationService.allocateBackorders(List.of(order), stockPool, NOW))
        .isInstanceOf(RuntimeException.class);

    assertThat(order.getStatus()).isEqualTo(OrderStatus.BACKORDERED);
    assertThat(stockPool.getReservedQuantity()).isZero();
  }

  @Test
  @DisplayName("Order 狀態不允許 allocation 時不可先修改 StockPool")
  void rejectsInvalidOrderStateBeforeReservingStock() {
    StockPool stockPool = stockPool(10, 0);
    Order order = pendingOrder(3);
    order.markAllocated(NOW.minusSeconds(1));
    order.releaseDomainEvents();

    assertThatThrownBy(() -> allocationService.allocate(order, stockPool, NOW))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Only pending or backordered orders can be allocated");

    assertThat(stockPool.getReservedQuantity()).isZero();
    assertThat(stockPool.availableToPromise()).isEqualTo(10);
  }

  /**
   * 一張跨兩個 SKU 的訂單。收單入口拒絕多行，因此只能以 {@code Order.rehydrate} 造——
   * 刻意直接寫出來而不藏進 fixture：這正是「入口進不來但儲存層允許」的東西。
   *
   * <p>這兩支測試守的是一條容易在重構中弄丟的界線：配貨只拿得到一個 {@code StockPool}，
   * 而這張單的需求有一半落在它之外。**檢查必須是「這張單的需求恰好只有這個池的 SKU」，
   * 不能是「包含」**——寫成包含的話，多行訂單會通過檢查，然後只扣其中一個 SKU 的量，
   * 而整張單被標為已配。那是靜默的錯，不會有任何測試失敗。
   */
  /** 同一個 SKU 的兩行。收單入口拒絕多行，只能以 rehydrate 造。 */
  private Order sameSkuTwoLineOrder(int firstQuantity, int secondQuantity) {
    UUID orderId = UUID.randomUUID();
    UUID ownerId = OrderFixtures.OWNER_ID;
    return Order.rehydrate(
        orderId,
        ownerId,
        "EXT-" + orderId,
        OrderFixtures.deliveryTerms(),
        List.of(
            OrderLine.rehydrate(UUID.randomUUID(), 1, ownerId, "SKU-1", firstQuantity,
                OrderStatus.PENDING, null),
            OrderLine.rehydrate(UUID.randomUUID(), 2, ownerId, "SKU-1", secondQuantity,
                OrderStatus.PENDING, null)),
        OrderStatus.PENDING,
        NOW.minusSeconds(10), null, null, null, null);
  }

  private Order twoSkuOrder() {
    UUID orderId = UUID.randomUUID();
    UUID ownerId = OrderFixtures.OWNER_ID;
    return Order.rehydrate(
        orderId,
        ownerId,
        "EXT-" + orderId,
        OrderFixtures.deliveryTerms(),
        List.of(
            OrderLine.rehydrate(
                UUID.randomUUID(), 1, ownerId, "SKU-1", 3, OrderStatus.PENDING, null),
            OrderLine.rehydrate(
                UUID.randomUUID(), 2, ownerId, "SKU-2", 5, OrderStatus.PENDING, null)),
        OrderStatus.PENDING,
        NOW.minusSeconds(10), null, null, null, null);
  }

  private Order pendingOrder(int quantity) {
    Order order = OrderFixtures.pendingOrder(UUID.randomUUID(), "SKU-1", quantity, NOW.minusSeconds(4));
    order.releaseDomainEvents();
    return order;
  }

  private Order backorderedOrder(int quantity, int secondsAgo) {
    Order order = OrderFixtures.pendingOrder(UUID.randomUUID(), "SKU-1", quantity, NOW.minusSeconds(5));
    order.markBackOrdered(NOW.minusSeconds(secondsAgo));
    order.releaseDomainEvents();
    return order;
  }

  private StockPool stockPool(int onHandQuantity, int reservedQuantity) {
    return new StockPool(java.util.UUID.randomUUID(), "SKU-1", onHandQuantity, reservedQuantity, 0L);
  }

}
