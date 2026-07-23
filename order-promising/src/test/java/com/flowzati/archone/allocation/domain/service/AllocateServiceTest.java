package com.flowzati.archone.allocation.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowzati.archone.allocation.domain.model.ReservationStatus;
import com.flowzati.archone.allocation.domain.model.StockPool;
import com.flowzati.archone.allocation.domain.model.StockReservation;
import com.flowzati.archone.ordering.domain.model.Order;
import com.flowzati.archone.ordering.domain.model.OrderStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("嚴格 FIFO allocation domain service")
class AllocateServiceTest {

  private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z");

  private AllocateService allocateService;
  private ReservationReleaseService releaseService;

  @BeforeEach
  void setUp() {
    allocateService = new AllocateService();
    releaseService = new ReservationReleaseService();
  }

  @Test
  @DisplayName("ATP 充足時應完整預留並將 Order 標記為 ALLOCATED")
  void allocatesCompleteOrderWhenAtpIsSufficient() {
    StockPool stockPool = stockPool(10, 2);
    Order order = pendingOrder(5);

    AllocationOutcome outcome = allocateService.allocate(order, stockPool, NOW);

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

    AllocationOutcome outcome = allocateService.allocate(order, stockPool, NOW);

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

    assertThat(allocateService.allocate(order, stockPool, NOW))
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

    List<Order> allocatedOrders = allocateService.allocateBackorders(
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

    List<Order> allocatedOrders = allocateService.allocateBackorders(
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

    List<Order> allocatedOrders = allocateService.allocateBackorders(
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
    AllocateService maximizingService =
        new AllocateService(AllocationSelector.maximizeFulfilledOrders());
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
  @DisplayName("釋放 ACTIVE reservation 時應同步釋放 StockPool quantity")
  void releasesActiveReservationAndReservedQuantityTogether() {
    StockPool stockPool = stockPool(10, 5);
    StockReservation reservation = activeReservation(stockPool.getId(), 3);

    boolean released = releaseService.release(reservation, stockPool, NOW);

    assertThat(released).isTrue();
    assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.RELEASED);
    assertThat(reservation.getReleasedAt()).isEqualTo(NOW);
    assertThat(stockPool.getReservedQuantity()).isEqualTo(2);
    assertThat(stockPool.availableToPromise()).isEqualTo(8);
  }

  @Test
  @DisplayName("重複釋放 reservation 應為 no-op 且不可重複增加 ATP")
  void repeatedReleaseIsNoOp() {
    StockPool stockPool = stockPool(10, 5);
    StockReservation reservation = activeReservation(stockPool.getId(), 3);
    assertThat(releaseService.release(reservation, stockPool, NOW)).isTrue();

    boolean releasedAgain =
        releaseService.release(reservation, stockPool, NOW.plusSeconds(1));

    assertThat(releasedAgain).isFalse();
    assertThat(reservation.getReleasedAt()).isEqualTo(NOW);
    assertThat(stockPool.getReservedQuantity()).isEqualTo(2);
  }

  @Test
  @DisplayName("釋放量超過 StockPool reserved quantity 時應拒絕且兩個 aggregate 都保持不變")
  void rejectsInconsistentReleaseBeforeMutatingEitherAggregate() {
    StockPool stockPool = stockPool(10, 2);
    StockReservation reservation = activeReservation(stockPool.getId(), 3);

    assertThatThrownBy(() -> releaseService.release(reservation, stockPool, NOW))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Quantity to release cannot exceed reserved quantity");

    assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.ACTIVE);
    assertThat(reservation.getReleasedAt()).isNull();
    assertThat(stockPool.getReservedQuantity()).isEqualTo(2);
  }

  @Test
  @DisplayName("Order 與 StockPool SKU 不一致時應在修改 aggregate 前拒絕")
  void rejectsSkuMismatchBeforeMutation() {
    StockPool stockPool = stockPool(10, 0);
    Order order = Order.place(UUID.randomUUID(), "OTHER-SKU", 3, NOW.minusSeconds(1));
    order.releaseDomainEvents();

    assertThatThrownBy(() -> allocateService.allocate(order, stockPool, NOW))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Order and stock pool SKU must match");

    assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
    assertThat(stockPool.getReservedQuantity()).isZero();
  }

  @Test
  @DisplayName("Order 狀態不允許 allocation 時不可先修改 StockPool")
  void rejectsInvalidOrderStateBeforeReservingStock() {
    StockPool stockPool = stockPool(10, 0);
    Order order = pendingOrder(3);
    order.markAllocated(NOW.minusSeconds(1));
    order.releaseDomainEvents();

    assertThatThrownBy(() -> allocateService.allocate(order, stockPool, NOW))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Only pending or backordered orders can be allocated");

    assertThat(stockPool.getReservedQuantity()).isZero();
    assertThat(stockPool.availableToPromise()).isEqualTo(10);
  }

  private Order pendingOrder(int quantity) {
    Order order = Order.place(UUID.randomUUID(), "SKU-1", quantity, NOW.minusSeconds(4));
    order.releaseDomainEvents();
    return order;
  }

  private Order backorderedOrder(int quantity, int secondsAgo) {
    Order order = Order.place(UUID.randomUUID(), "SKU-1", quantity, NOW.minusSeconds(5));
    order.markBackOrdered(NOW.minusSeconds(secondsAgo));
    order.releaseDomainEvents();
    return order;
  }

  private StockPool stockPool(int onHandQuantity, int reservedQuantity) {
    return new StockPool(1L, "SKU-1", onHandQuantity, reservedQuantity, 0L);
  }

  private StockReservation activeReservation(long stockPoolId, int quantity) {
    return StockReservation.create(
        UUID.randomUUID(),
        UUID.randomUUID(),
        stockPoolId,
        quantity,
        NOW.minusSeconds(1)
    );
  }
}
