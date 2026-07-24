package com.flowzati.archone.allocation.application.usecase;

import com.flowzati.archone.allocation.application.coordinator.OrderAllocationCoordinator;
import com.flowzati.archone.allocation.application.command.ReplenishStockCommand;
import com.flowzati.archone.allocation.application.event.StockReplenishedIntegrationEvent;
import com.flowzati.archone.allocation.domain.model.StockPool;
import com.flowzati.archone.allocation.domain.model.ReservationStatus;
import com.flowzati.archone.allocation.domain.model.StockReservation;
import com.flowzati.archone.allocation.domain.repository.StockPoolRepository;
import com.flowzati.archone.allocation.domain.repository.StockReservationRepository;
import com.flowzati.archone.allocation.domain.service.AllocationService;
import com.flowzati.archone.common.inbox.InboxRepo;
import com.flowzati.archone.common.inbox.MessageMetadata;
import com.flowzati.archone.ordering.domain.model.Order;
import com.flowzati.archone.ordering.domain.model.OrderStatus;
import com.flowzati.archone.ordering.domain.event.OrderAllocated;
import com.flowzati.archone.ordering.domain.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
  private InboxRepo inboxRepo;
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

    inboxRepo = mock(InboxRepo.class);
    clock = Clock.fixed(fixedNow, ZoneId.of("UTC"));

    replenishmentUsecase = new ReplenishmentUsecase(
        clock,
        inboxRepo,
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

    when(inboxRepo.claimIfNew(message(eventId))).thenReturn(true);

    // 初始庫存池為 0
    StockPool stockPool = new StockPool(java.util.UUID.randomUUID(), sku, 0, 0, 0L);
    when(stockPoolRepository.findBySku(sku)).thenReturn(Optional.of(stockPool));

    Order backorderedOrder = backorderedOrder(sku, 5);
    List<Order> backorders = List.of(backorderedOrder);
    when(orderRepository.findBackordersBySkuInFifoOrder(sku))
        .thenReturn(backorders);

    // Act
    replenishmentUsecase.handle(command(event), message(eventId));

    // Assert
    verify(inboxRepo).claimIfNew(message(eventId));
    verify(stockPoolRepository).findBySku(sku);
    verify(orderRepository).findBackordersBySkuInFifoOrder(sku);
    verify(orderRepository).save(backorderedOrder);
    verify(stockPoolRepository).save(stockPool);
    ArgumentCaptor<StockReservation> reservationCaptor =
        ArgumentCaptor.forClass(StockReservation.class);
    verify(stockReservationRepository).save(reservationCaptor.capture());
    verify(eventPublisher, atLeastOnce()).publishEvent(any(OrderAllocated.class));

    assertThat(backorderedOrder.getStatus()).isEqualTo(OrderStatus.ALLOCATED);
    assertThat(backorderedOrder.getAllocatedAt()).isEqualTo(fixedNow);
    assertThat(stockPool.availableToPromise()).isEqualTo(5); // 0 + 10 - 5 = 5
    assertThat(reservationCaptor.getValue()).satisfies(reservation -> {
      assertThat(reservation.getOrderId()).isEqualTo(backorderedOrder.getId());
      assertThat(reservation.getStockPoolId()).isEqualTo(stockPool.getId());
      assertThat(reservation.getQuantity()).isEqualTo(5);
      assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.ACTIVE);
      assertThat(reservation.getReservedAt()).isEqualTo(fixedNow);
    });
  }

  @Test
  @DisplayName("當補充庫存不足以分配所有訂單時，應按順序部分分配")
  void shouldAllocatePartiallyWhenReplenishedStockIsLimited() {
    // Arrange
    UUID eventId = UUID.randomUUID();
    String sku = "SKU-1";
    int replenishedQuantity = 5;
    StockReplenishedIntegrationEvent event = new StockReplenishedIntegrationEvent(eventId, sku, replenishedQuantity);

    when(inboxRepo.claimIfNew(message(eventId))).thenReturn(true);

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
    replenishmentUsecase.handle(command(event), message(eventId));

    // Assert
    // 0 + 5 = 5
    // order1 (3) <= 5 -> 成功，剩餘 2
    // order2 (4) > 2 -> 失敗，狀態應維持 BACKORDERED
    assertThat(order1.getStatus()).isEqualTo(OrderStatus.ALLOCATED);
    assertThat(order2.getStatus()).isEqualTo(OrderStatus.BACKORDERED);
    assertThat(stockPool.availableToPromise()).isEqualTo(2);

    verify(orderRepository).save(order1);
    verify(stockReservationRepository).save(any(StockReservation.class));
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
    when(inboxRepo.claimIfNew(message(eventId))).thenReturn(true);
    when(stockPoolRepository.findBySku(sku)).thenReturn(Optional.of(stockPool));
    when(orderRepository.findBackordersBySkuInFifoOrder(sku))
        .thenReturn(List.of());

    // Act
    replenishmentUsecase.handle(command(event), message(eventId));

    // Assert
    verify(stockPoolRepository).findBySku(sku);
    verify(stockPoolRepository).save(stockPool);
    assertThat(stockPool.availableToPromise()).isEqualTo(10);
    verify(orderRepository, never()).save(any());
    verify(stockReservationRepository, never()).save(any());
    verify(eventPublisher, never()).publishEvent(any());
  }

  @Test
  @DisplayName("當事件已被處理過時，不應重複執行補充邏輯")
  void shouldDoNothingWhenEventIsAlreadyProcessed() {
    // Arrange
    UUID eventId = UUID.randomUUID();
    StockReplenishedIntegrationEvent event = new StockReplenishedIntegrationEvent(eventId, "SKU-1", 10);
    when(inboxRepo.claimIfNew(message(eventId))).thenReturn(false);

    // Act
    replenishmentUsecase.handle(command(event), message(eventId));

    // Assert
    verify(inboxRepo).claimIfNew(message(eventId));
    verifyNoInteractions(stockPoolRepository, orderRepository, stockReservationRepository, eventPublisher);
  }

  @Test
  @DisplayName("找不到對應 SKU 的 StockPool 時應失敗且不查詢 backorders")
  void shouldFailWhenStockPoolDoesNotExist() {
    UUID eventId = UUID.randomUUID();
    StockReplenishedIntegrationEvent event =
        new StockReplenishedIntegrationEvent(eventId, "UNKNOWN-SKU", 10);
    when(inboxRepo.claimIfNew(message(eventId))).thenReturn(true);
    when(stockPoolRepository.findBySku("UNKNOWN-SKU")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> replenishmentUsecase.handle(command(event), message(eventId)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("StockPool not found for SKU: UNKNOWN-SKU");

    verifyNoInteractions(orderRepository, stockReservationRepository, eventPublisher);
  }

  private Order backorderedOrder(String sku, int quantity) {
    Order order = Order.place(UUID.randomUUID(), sku, quantity, fixedNow.minusSeconds(2));
    order.markBackOrdered(fixedNow.minusSeconds(1));
    order.releaseDomainEvents();
    return order;
  }

  private MessageMetadata message(UUID eventId) {
    return new MessageMetadata(eventId, StockReplenishedIntegrationEvent.class.getSimpleName());
  }

  private ReplenishStockCommand command(StockReplenishedIntegrationEvent event) {
    return new ReplenishStockCommand(event.getSku(), event.getQuantity());
  }

}
