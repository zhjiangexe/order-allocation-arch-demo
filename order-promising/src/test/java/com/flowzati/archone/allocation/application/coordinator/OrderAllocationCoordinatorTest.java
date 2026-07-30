package com.flowzati.archone.allocation.application.coordinator;

import com.flowzati.archone.allocation.domain.event.OrderAllocationCompleted;
import com.flowzati.archone.allocation.domain.model.ReservationStatus;
import com.flowzati.archone.allocation.domain.model.StockPool;
import com.flowzati.archone.allocation.domain.model.StockFixtures;
import com.flowzati.archone.allocation.domain.model.StockReservation;
import com.flowzati.archone.allocation.domain.repository.StockPoolRepository;
import com.flowzati.archone.allocation.domain.repository.StockReservationRepository;
import com.flowzati.archone.allocation.domain.service.AllocationOutcome;
import com.flowzati.archone.allocation.domain.service.AllocationService;
import com.flowzati.archone.ordering.domain.event.OrderAllocated;
import com.flowzati.archone.ordering.domain.model.Order;
import com.flowzati.archone.ordering.domain.model.OrderStatus;
import com.flowzati.archone.ordering.domain.repository.OrderRepository;
import com.flowzati.archone.testsupport.OrderFixtures;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OrderAllocationCoordinatorTest {

  private static final LocalDate NEAR_EXPIRY = LocalDate.of(2026, 8, 31);
  private static final LocalDate FAR_EXPIRY = LocalDate.of(2027, 1, 31);

  private final Instant now = Instant.parse("2026-07-23T00:00:00Z");

  private StockPoolRepository stockPoolRepository;
  private OrderRepository orderRepository;
  private StockReservationRepository stockReservationRepository;
  private ApplicationEventPublisher eventPublisher;
  private OrderAllocationCoordinator coordinator;

  @BeforeEach
  void setUp() {
    stockPoolRepository = mock(StockPoolRepository.class);
    orderRepository = mock(OrderRepository.class);
    stockReservationRepository = mock(StockReservationRepository.class);
    eventPublisher = mock(ApplicationEventPublisher.class);
    coordinator = new OrderAllocationCoordinator(
        new AllocationService(),
        stockPoolRepository,
        orderRepository,
        stockReservationRepository,
        eventPublisher
    );
  }

  @Test
  @DisplayName("單筆訂單分配成功時應統一儲存 Order、批次與 Reservation")
  void shouldPersistAllocationWhenAllocationSucceeds() {
    Order order = pendingOrder("SKU-1", 5);
    StockPool batch = stockPool("SKU-1", 10);

    AllocationOutcome outcome = coordinator.allocateOrder(order, List.of(batch), now);

    assertThat(outcome).isEqualTo(AllocationOutcome.ALLOCATED);
    assertThat(order.getStatus()).isEqualTo(OrderStatus.ALLOCATED);
    assertThat(batch.getReservedQuantity()).isEqualTo(5);
    verify(orderRepository).save(order);
    verify(stockPoolRepository).save(batch);

    ArgumentCaptor<StockReservation> reservationCaptor =
        ArgumentCaptor.forClass(StockReservation.class);
    verify(stockReservationRepository).save(reservationCaptor.capture());
    StockReservation reservation = reservationCaptor.getValue();
    assertThat(reservation.getOrderLineId()).isEqualTo(order.getLines().get(0).getId());
    assertThat(reservation.getStockPoolId()).isEqualTo(batch.getId());
    assertThat(reservation.getQuantity()).isEqualTo(5);

    verify(eventPublisher).publishEvent(any(OrderAllocated.class));
    ArgumentCaptor<OrderAllocationCompleted> eventCaptor =
        ArgumentCaptor.forClass(OrderAllocationCompleted.class);
    verify(eventPublisher).publishEvent(eventCaptor.capture());
    // 事件只通知「這張單配好了」，不攜帶配到哪些批——那份資訊在 stock_reservations 裡，
    // 且取消會改變它，事件抄一份只會多一個對不上的來源。
    assertThat(eventCaptor.getValue()).isEqualTo(
        new OrderAllocationCompleted(order.getId(), now));
  }

  @Test
  @DisplayName("一條行吃到兩批時應產生兩筆預留，數量加總等於該行的量")
  void shouldCreateOneReservationPerBatchTaken() {
    Order order = pendingOrder("SKU-1", 80);
    StockPool near = StockFixtures.batchExpiringOn("SKU-1", NEAR_EXPIRY, 60, 0);
    StockPool far = StockFixtures.batchExpiringOn("SKU-1", FAR_EXPIRY, 40, 0);

    coordinator.allocateOrder(order, List.of(near, far), now);

    ArgumentCaptor<StockReservation> captor = ArgumentCaptor.forClass(StockReservation.class);
    verify(stockReservationRepository, org.mockito.Mockito.times(2)).save(captor.capture());
    // 粒度是行 × 批。摺成一張單一筆會丟掉「哪一批是為哪一條行鎖的」，而出貨時要的正是它。
    assertThat(captor.getAllValues()).hasSize(2);
    assertThat(captor.getAllValues()).allSatisfy(reservation ->
        assertThat(reservation.getOrderLineId()).isEqualTo(order.getLines().get(0).getId()));
    assertThat(captor.getAllValues().stream()
        .mapToInt(StockReservation::getQuantity).sum()).isEqualTo(80);
  }

  @Test
  @DisplayName("批次寫入應依 (SKU, 效期, 入庫日, id) 排序，不得跟著輸入順序走")
  void shouldWriteBatchesInADeterministicOrderRegardlessOfInputOrder() {
    Order order = pendingOrder("SKU-1", 100);
    StockPool near = StockFixtures.batchExpiringOn("SKU-1", NEAR_EXPIRY, 60, 0);
    StockPool far = StockFixtures.batchExpiringOn("SKU-1", FAR_EXPIRY, 40, 0);

    // 刻意把遠效期放前面。FEFO 查詢碰巧已經排好，但那是查詢的實作細節；釋放與補貨兩條路徑
    // 的集合來源完全不同，寫入順序必須自己保證，否則相反順序的兩個交易會互相等成死鎖。
    coordinator.allocateOrder(order, List.of(far, near), now);

    ArgumentCaptor<StockPool> captor = ArgumentCaptor.forClass(StockPool.class);
    verify(stockPoolRepository, org.mockito.Mockito.times(2)).save(captor.capture());
    assertThat(captor.getAllValues()).containsExactly(near, far);
  }

  @Test
  @DisplayName("單筆訂單分配失敗時不應儲存未變更的批次")
  void shouldNotSaveStockPoolWhenAllocationFails() {
    Order order = pendingOrder("SKU-1", 5);
    StockPool batch = stockPool("SKU-1", 2);

    AllocationOutcome outcome = coordinator.allocateOrder(order, List.of(batch), now);

    assertThat(outcome).isEqualTo(AllocationOutcome.INSUFFICIENT_ATP);
    assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
    assertThat(batch.getReservedQuantity()).isZero();
    verify(stockPoolRepository, never()).save(batch);
    verifyNoInteractions(orderRepository, stockReservationRepository, eventPublisher);
  }

  @Test
  @DisplayName("沒有任何可售批時不應寫入任何東西，也不應為了問「為什麼」再查一次庫存")
  void shouldWriteNothingWhenThereIsNoAllocatableStock() {
    Order order = pendingOrder("SKU-1", 5);

    // 「一批都沒有」與「有批但全部過期」的差別屬於庫存狀態，由庫存頁每次重算；訂單這一側
    // 不再為了那個區別多付一次查詢，因為它算出來也沒有不會過期的地方可以放。
    assertThat(coordinator.allocateOrder(order, List.of(), now))
        .isEqualTo(AllocationOutcome.NO_ALLOCATABLE_STOCK);
    verifyNoInteractions(
        stockPoolRepository, orderRepository, stockReservationRepository, eventPublisher);
  }

  @Test
  @DisplayName("ATP 不足時應標記欠單並只發布 Domain Event")
  void shouldBackorderOrderWhenAllocationIsInsufficient() {
    Order order = pendingOrder("SKU-1", 5);

    coordinator.backorderOrder(order, now);

    assertThat(order.getStatus()).isEqualTo(OrderStatus.BACKORDERED);
    verify(orderRepository).save(order);
    verify(eventPublisher).publishEvent(any(
        com.flowzati.archone.ordering.domain.event.OrderBackordered.class));
    verifyNoInteractions(stockPoolRepository, stockReservationRepository);
  }

  @Test
  @DisplayName("喚醒時沒有任何 backorder 就不應寫入任何東西")
  void shouldWriteNothingWhenThereAreNoBackorders() {
    StockPool batch = stockPool("SKU-1", 10);

    List<Order> result = coordinator.allocateBackorders(List.of(), List.of(batch), now);

    // 補貨本身已經在 usecase 的 upsert 寫進去了，這裡再存一次只是多一次無謂的寫入與衝突。
    assertThat(result).isEmpty();
    verifyNoInteractions(
        stockPoolRepository, orderRepository, stockReservationRepository, eventPublisher);
  }

  @Test
  @DisplayName("釋放一張單的多筆預留時應全部釋放，並統一儲存批次與預留")
  void shouldReleaseEveryReservationOfAnOrder() {
    StockPool near = StockFixtures.batchExpiringOn("SKU-1", NEAR_EXPIRY, 60, 60);
    StockPool far = StockFixtures.batchExpiringOn("SKU-1", FAR_EXPIRY, 40, 20);
    UUID lineId = UUID.randomUUID();
    StockReservation onNear = StockReservation.create(
        UUID.randomUUID(), lineId, near.getId(), 60, now.minusSeconds(1));
    StockReservation onFar = StockReservation.create(
        UUID.randomUUID(), lineId, far.getId(), 20, now.minusSeconds(1));

    boolean released = coordinator.releaseReservations(
        List.of(onNear, onFar), Map.of(near.getId(), near, far.getId(), far), now);

    // 只放第一筆的話，遠效期那 20 件會永遠鎖著，而且不會有任何錯誤浮現。
    assertThat(released).isTrue();
    assertThat(onNear.getStatus()).isEqualTo(ReservationStatus.RELEASED);
    assertThat(onFar.getStatus()).isEqualTo(ReservationStatus.RELEASED);
    assertThat(near.getReservedQuantity()).isZero();
    assertThat(far.getReservedQuantity()).isZero();
    verify(stockPoolRepository).save(near);
    verify(stockPoolRepository).save(far);
    verify(stockReservationRepository).save(onNear);
    verify(stockReservationRepository).save(onFar);
    verifyNoInteractions(orderRepository, eventPublisher);
  }

  @Test
  @DisplayName("重複釋放 reservation 時應為 no-op 且不可重複增加 ATP")
  void shouldDoNothingWhenReservationHasAlreadyBeenReleased() {
    StockPool batch = StockFixtures.unexpiredBatch("SKU-1", 10, 3);
    StockReservation reservation = StockReservation.create(
        UUID.randomUUID(), UUID.randomUUID(), batch.getId(), 3, now.minusSeconds(1));
    Map<UUID, StockPool> batches = Map.of(batch.getId(), batch);
    coordinator.releaseReservations(List.of(reservation), batches, now);

    boolean releasedAgain =
        coordinator.releaseReservations(List.of(reservation), batches, now.plusSeconds(1));

    assertThat(releasedAgain).isFalse();
    assertThat(batch.getReservedQuantity()).isZero();
  }

  @Test
  @DisplayName("釋放量超過已預留數量時應拒絕且兩個 aggregate 都保持不變")
  void shouldRejectInconsistentReleaseBeforeMutatingEitherAggregate() {
    StockPool batch = StockFixtures.unexpiredBatch("SKU-1", 10, 2);
    StockReservation reservation = StockReservation.create(
        UUID.randomUUID(), UUID.randomUUID(), batch.getId(), 3, now.minusSeconds(1));

    assertThatThrownBy(() -> coordinator.releaseReservations(
        List.of(reservation), Map.of(batch.getId(), batch), now))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Quantity to release cannot exceed reserved quantity");

    assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.ACTIVE);
    assertThat(batch.getReservedQuantity()).isEqualTo(2);
    verifyNoInteractions(stockPoolRepository, stockReservationRepository);
  }

  @Test
  @DisplayName("預留指向的批沒被帶進來時應拒絕，不可靜默略過")
  void shouldRejectAReservationWhoseBatchWasNotSupplied() {
    StockReservation orphan = StockReservation.create(
        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 3, now.minusSeconds(1));

    assertThatThrownBy(
        () -> coordinator.releaseReservations(List.of(orphan), Map.of(), now))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private Order pendingOrder(String sku, int quantity) {
    Order order =
        OrderFixtures.pendingOrder(UUID.randomUUID(), sku, quantity, now.minusSeconds(1));
    order.releaseDomainEvents();
    return order;
  }

  private StockPool stockPool(String sku, int onHandQuantity) {
    return StockFixtures.unexpiredBatch(sku, onHandQuantity, 0);
  }
}
