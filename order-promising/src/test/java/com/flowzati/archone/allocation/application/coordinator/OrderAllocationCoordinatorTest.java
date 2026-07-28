package com.flowzati.archone.allocation.application.coordinator;

import com.flowzati.archone.allocation.domain.event.OrderAllocationCompleted;
import com.flowzati.archone.allocation.domain.model.StockPool;
import com.flowzati.archone.allocation.domain.model.StockReservation;
import com.flowzati.archone.allocation.domain.model.ReservationStatus;
import com.flowzati.archone.allocation.domain.repository.StockPoolRepository;
import com.flowzati.archone.allocation.domain.repository.StockReservationRepository;
import com.flowzati.archone.allocation.domain.service.AllocationService;
import com.flowzati.archone.ordering.domain.event.OrderAllocated;
import com.flowzati.archone.ordering.domain.model.Order;
import com.flowzati.archone.ordering.domain.model.OrderStatus;
import com.flowzati.archone.ordering.domain.repository.OrderRepository;
import com.flowzati.archone.testsupport.OrderFixtures;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
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

class OrderAllocationCoordinatorTest {

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
  @DisplayName("單筆訂單分配成功時應統一儲存 Order、StockPool 與 Reservation")
  void shouldPersistAllocationWhenAllocationSucceeds() {
    Order order = pendingOrder("SKU-1", 5);
    StockPool stockPool = stockPool("SKU-1", 10);

    Optional<StockReservation> result = coordinator.allocateOrder(order, stockPool, now);

    assertThat(result).hasValueSatisfying(reservation -> {
      assertThat(reservation.getOrderId()).isEqualTo(order.getId());
      assertThat(reservation.getStockPoolId()).isEqualTo(stockPool.getId());
      assertThat(reservation.getQuantity()).isEqualTo(order.getDemandFor(stockPool.getSku()));
    });
    assertThat(order.getStatus()).isEqualTo(OrderStatus.ALLOCATED);
    assertThat(stockPool.getReservedQuantity()).isEqualTo(5);
    verify(orderRepository).save(order);
    verify(stockPoolRepository).save(stockPool);
    verify(stockReservationRepository).save(result.orElseThrow());
    verify(eventPublisher).publishEvent(any(OrderAllocated.class));
    ArgumentCaptor<OrderAllocationCompleted> eventCaptor =
        ArgumentCaptor.forClass(OrderAllocationCompleted.class);
    verify(eventPublisher).publishEvent(eventCaptor.capture());
    assertThat(eventCaptor.getValue()).isEqualTo(new OrderAllocationCompleted(
        order.getId(),
        result.orElseThrow().getId(),
        stockPool.getSku(),
        order.getDemandFor(stockPool.getSku()),
        now
    ));
  }

  @Test
  @DisplayName("單筆訂單分配失敗時不應儲存未變更的 StockPool")
  void shouldNotSaveStockPoolWhenAllocationFails() {
    Order order = pendingOrder("SKU-1", 5);
    StockPool stockPool = stockPool("SKU-1", 2);

    Optional<StockReservation> result = coordinator.allocateOrder(order, stockPool, now);

    assertThat(result).isEmpty();
    assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
    assertThat(stockPool.getReservedQuantity()).isZero();
    verify(stockPoolRepository, never()).save(stockPool);
    verifyNoInteractions(orderRepository, stockReservationRepository, eventPublisher);
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
  @DisplayName("補貨後即使沒有 backorder 也應儲存 StockPool")
  void shouldSaveReplenishedStockPoolWhenThereAreNoBackorders() {
    StockPool stockPool = stockPool("SKU-1", 0);

    List<Order> result = coordinator.replenishAndAllocateBackorders(
        List.of(), stockPool,
        10,
        now
    );

    assertThat(result).isEmpty();
    assertThat(stockPool.getOnHandQuantity()).isEqualTo(10);
    verify(stockPoolRepository).save(stockPool);
    verifyNoInteractions(orderRepository, stockReservationRepository, eventPublisher);
  }

  @Test
  @DisplayName("釋放 reservation 時應統一儲存 StockPool 與 Reservation")
  void shouldPersistStockPoolAndReservationWhenReservationIsReleased() {
    StockPool stockPool = new StockPool(UUID.randomUUID(), "SKU-1", 10, 3, 0L);
    StockReservation reservation = StockReservation.create(
        UUID.randomUUID(), UUID.randomUUID(), stockPool.getId(), 3, now.minusSeconds(1));

    boolean released = coordinator.releaseReservation(reservation, stockPool, now);

    assertThat(released).isTrue();
    assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.RELEASED);
    assertThat(stockPool.getReservedQuantity()).isZero();
    verify(stockPoolRepository).save(stockPool);
    verify(stockReservationRepository).save(reservation);
    verifyNoInteractions(orderRepository, eventPublisher);
  }

  @Test
  @DisplayName("重複釋放 reservation 時應為 no-op 且不可重複增加 ATP")
  void shouldDoNothingWhenReservationHasAlreadyBeenReleased() {
    StockPool stockPool = new StockPool(UUID.randomUUID(), "SKU-1", 10, 3, 0L);
    StockReservation reservation = StockReservation.create(
        UUID.randomUUID(), UUID.randomUUID(), stockPool.getId(), 3, now.minusSeconds(1));
    coordinator.releaseReservation(reservation, stockPool, now);

    boolean releasedAgain = coordinator.releaseReservation(reservation, stockPool, now.plusSeconds(1));

    assertThat(releasedAgain).isFalse();
    assertThat(stockPool.getReservedQuantity()).isZero();
  }

  @Test
  @DisplayName("釋放量超過已預留數量時應拒絕且兩個 aggregate 都保持不變")
  void shouldRejectInconsistentReleaseBeforeMutatingEitherAggregate() {
    StockPool stockPool = new StockPool(UUID.randomUUID(), "SKU-1", 10, 2, 0L);
    StockReservation reservation = StockReservation.create(
        UUID.randomUUID(), UUID.randomUUID(), stockPool.getId(), 3, now.minusSeconds(1));

    assertThatThrownBy(() -> coordinator.releaseReservation(reservation, stockPool, now))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Quantity to release cannot exceed reserved quantity");

    assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.ACTIVE);
    assertThat(stockPool.getReservedQuantity()).isEqualTo(2);
    verifyNoInteractions(stockPoolRepository, stockReservationRepository);
  }

  private Order pendingOrder(String sku, int quantity) {
    Order order = OrderFixtures.pendingOrder(UUID.randomUUID(), sku, quantity, now.minusSeconds(1));
    order.releaseDomainEvents();
    return order;
  }

  private StockPool stockPool(String sku, int onHandQuantity) {
    return new StockPool(java.util.UUID.randomUUID(), sku, onHandQuantity, 0, 0L);
  }
}
