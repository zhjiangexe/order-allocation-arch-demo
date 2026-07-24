package com.flowzati.archone.allocation.application.coordinator;

import com.flowzati.archone.allocation.domain.event.OrderAllocationCompleted;
import com.flowzati.archone.allocation.domain.model.StockPool;
import com.flowzati.archone.allocation.domain.model.StockReservation;
import com.flowzati.archone.allocation.domain.repository.StockPoolRepository;
import com.flowzati.archone.allocation.domain.repository.StockReservationRepository;
import com.flowzati.archone.allocation.domain.service.AllocationService;
import com.flowzati.archone.ordering.domain.event.OrderAllocated;
import com.flowzati.archone.ordering.domain.model.Order;
import com.flowzati.archone.ordering.domain.model.OrderStatus;
import com.flowzati.archone.ordering.domain.repository.OrderRepository;
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
      assertThat(reservation.getQuantity()).isEqualTo(order.getQuantity());
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
        order.getSku(),
        order.getQuantity(),
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

  private Order pendingOrder(String sku, int quantity) {
    Order order = Order.place(UUID.randomUUID(), sku, quantity, now.minusSeconds(1));
    order.releaseDomainEvents();
    return order;
  }

  private StockPool stockPool(String sku, int onHandQuantity) {
    return new StockPool(java.util.UUID.randomUUID(), sku, onHandQuantity, 0, 0L);
  }
}
