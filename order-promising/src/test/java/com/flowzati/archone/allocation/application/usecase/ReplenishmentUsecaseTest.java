package com.flowzati.archone.allocation.application.usecase;

import com.flowzati.archone.allocation.application.coordinator.OrderAllocationCoordinator;
import com.flowzati.archone.allocation.application.event.StockReplenishedIntegrationEvent;
import com.flowzati.archone.allocation.domain.model.StockPool;
import com.flowzati.archone.allocation.domain.repository.StockPoolRepository;
import com.flowzati.archone.allocation.domain.repository.StockReservationRepository;
import com.flowzati.archone.allocation.domain.service.AllocationService;
import com.flowzati.archone.common.inbox.Inbox;
import com.flowzati.archone.ordering.domain.model.Order;
import com.flowzati.archone.ordering.domain.model.OrderStatus;
import com.flowzati.archone.ordering.domain.event.OrderAllocated;
import com.flowzati.archone.ordering.domain.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ReplenishmentUsecaseTest {

  private StockPoolRepository stockPoolRepository;
  private OrderRepository orderRepository;
  private StockReservationRepository stockReservationRepository;
  private OrderAllocationCoordinator allocationService;
  private Inbox inbox;
  private ApplicationEventPublisher eventPublisher;
  private Clock clock;
  private ReplenishmentUsecase replenishmentUsecase;

  private final Instant fixedNow = Instant.parse("2026-07-21T10:00:00Z");

  @BeforeEach
  void setUp() {
    stockPoolRepository = mock(StockPoolRepository.class);
    orderRepository = mock(OrderRepository.class);
    stockReservationRepository = mock(StockReservationRepository.class);
    eventPublisher = mock(ApplicationEventPublisher.class);

    AllocationService allocationService = new AllocationService();

    this.allocationService = new OrderAllocationCoordinator(
        allocationService,
        stockPoolRepository,
        orderRepository,
        stockReservationRepository,
        eventPublisher
    );

    inbox = mock(Inbox.class);
    clock = Clock.fixed(fixedNow, ZoneId.of("UTC"));

    replenishmentUsecase = new ReplenishmentUsecase(
        clock,
        inbox,
        orderRepository,
        stockPoolRepository,
        this.allocationService
    );
  }

  @Test
  @DisplayName("當收到新的補充事件時，應補充庫存並分配等待中的訂單")
  void shouldReplenishAndAllocateWhenEventIsNew() {
    // Arrange
    UUID eventId = UUID.randomUUID();
    String sku = "SKU-1";
    int quantity = 10;
    StockReplenishedIntegrationEvent event = new StockReplenishedIntegrationEvent(eventId, sku, quantity);

    when(inbox.claimIfNew(eventId)).thenReturn(true);

    // 初始庫存池為 0
    StockPool stockPool = new StockPool(java.util.UUID.randomUUID(), sku, 0, 0, 0L);
    when(stockPoolRepository.findBySku(sku)).thenReturn(Optional.of(stockPool));

    Order backorderedOrder = backorderedOrder(sku, 5);
    List<Order> backorders = List.of(backorderedOrder);
    when(orderRepository.findBackordersBySkuInFifoOrder(sku))
        .thenReturn(backorders);

    // Act
    replenishmentUsecase.handle(event);

    // Assert
    verify(inbox).claimIfNew(eventId);
    verify(stockPoolRepository).findBySku(sku);
    verify(orderRepository).findBackordersBySkuInFifoOrder(sku);
    verify(orderRepository).save(backorderedOrder);
    verify(stockPoolRepository).save(stockPool);
    verify(eventPublisher, atLeastOnce()).publishEvent(any(OrderAllocated.class));

    assertThat(backorderedOrder.getStatus()).isEqualTo(OrderStatus.ALLOCATED);
    assertThat(backorderedOrder.getAllocatedAt()).isEqualTo(fixedNow);
    assertThat(stockPool.availableToPromise()).isEqualTo(5); // 0 + 10 - 5 = 5
  }

  @Test
  @DisplayName("當補充庫存不足以分配所有訂單時，應按順序部分分配")
  void shouldAllocatePartiallyWhenReplenishedStockIsLimited() {
    // Arrange
    UUID eventId = UUID.randomUUID();
    String sku = "SKU-1";
    int replenishedQuantity = 5;
    StockReplenishedIntegrationEvent event = new StockReplenishedIntegrationEvent(eventId, sku, replenishedQuantity);

    when(inbox.claimIfNew(eventId)).thenReturn(true);

    // 庫存池初始為 0
    StockPool stockPool = new StockPool(java.util.UUID.randomUUID(), sku, 0, 0, 0L);
    when(stockPoolRepository.findBySku(sku)).thenReturn(Optional.of(stockPool));

    // 有兩筆訂單，第一筆要 3 個，第二筆要 4 個 (總共 7 個，大於補充量 5)
    Order order1 = backorderedOrder(sku, 3);
    Order order2 = backorderedOrder(sku, 4);
    List<Order> backorders = List.of(order1, order2);
    when(orderRepository.findBackordersBySkuInFifoOrder(sku))
        .thenReturn(backorders);

    // Act
    replenishmentUsecase.handle(event);

    // Assert
    // 0 + 5 = 5
    // order1 (3) <= 5 -> 成功，剩餘 2
    // order2 (4) > 2 -> 失敗，狀態應維持 BACKORDERED
    assertThat(order1.getStatus()).isEqualTo(OrderStatus.ALLOCATED);
    assertThat(order2.getStatus()).isEqualTo(OrderStatus.BACKORDERED);
    assertThat(stockPool.availableToPromise()).isEqualTo(2);

    verify(orderRepository).save(order1);
    // 在新設計中，補充失敗的訂單不會被 save，因為狀態沒變
    verify(orderRepository, never()).save(order2);
    verify(stockPoolRepository).save(stockPool);
  }

  @Test
  @DisplayName("當沒有待處理訂單時，應僅更新庫存而不進行分配")
  void shouldOnlyReplenishWhenNoPendingOrders() {
    // Arrange
    UUID eventId = UUID.randomUUID();
    String sku = "SKU-1";
    int quantity = 10;
    StockReplenishedIntegrationEvent event = new StockReplenishedIntegrationEvent(eventId, sku, quantity);

    StockPool stockPool = new StockPool(java.util.UUID.randomUUID(), sku, 0, 0, 0L);
    when(inbox.claimIfNew(eventId)).thenReturn(true);
    when(stockPoolRepository.findBySku(sku)).thenReturn(Optional.of(stockPool));
    when(orderRepository.findBackordersBySkuInFifoOrder(sku))
        .thenReturn(List.of());

    // Act
    replenishmentUsecase.handle(event);

    // Assert
    verify(stockPoolRepository).findBySku(sku);
    verify(stockPoolRepository).save(stockPool);
    assertThat(stockPool.availableToPromise()).isEqualTo(10);
    verify(orderRepository, never()).save(any());
    verify(eventPublisher, never()).publishEvent(any());
  }

  @Test
  @DisplayName("當事件已被處理過時，不應重複執行補充邏輯")
  void shouldDoNothingWhenEventIsAlreadyProcessed() {
    // Arrange
    UUID eventId = UUID.randomUUID();
    StockReplenishedIntegrationEvent event = new StockReplenishedIntegrationEvent(eventId, "SKU-1", 10);
    when(inbox.claimIfNew(eventId)).thenReturn(false);

    // Act
    replenishmentUsecase.handle(event);

    // Assert
    verify(inbox).claimIfNew(eventId);
    verifyNoInteractions(stockPoolRepository, orderRepository, eventPublisher);
  }

  private Order backorderedOrder(String sku, int quantity) {
    Order order = Order.place(UUID.randomUUID(), sku, quantity, fixedNow.minusSeconds(2));
    order.markBackOrdered(fixedNow.minusSeconds(1));
    order.releaseDomainEvents();
    return order;
  }

}
