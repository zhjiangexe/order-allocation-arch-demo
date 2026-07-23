package com.flowzati.archone.allocation.application.usecase;

import com.flowzati.archone.allocation.application.coordinator.OrderAllocationCoordinator;
import com.flowzati.archone.allocation.application.event.BackorderCreatedIntegrationEvent;
import com.flowzati.archone.allocation.domain.model.StockPool;
import com.flowzati.archone.allocation.domain.repository.StockPoolRepository;
import com.flowzati.archone.common.inbox.Inbox;
import com.flowzati.archone.ordering.domain.event.OrderBackordered;
import com.flowzati.archone.ordering.domain.model.Order;
import com.flowzati.archone.ordering.domain.model.OrderStatus;
import com.flowzati.archone.ordering.domain.repository.OrderRepository;
import com.flowzati.archone.ordering.application.event.OrderPlacedIntegrationEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoInteractions;

class AllocateOrderUsecaseTest {

  private AllocateOrderUsecase usecase;
  private Inbox inbox;
  private OrderRepository orderRepository;
  private StockPoolRepository stockPoolRepository;
  private OrderAllocationCoordinator allocationService;
  private ApplicationEventPublisher eventPublisher;
  private Clock clock;

  private final Instant fixedNow = Instant.parse("2026-07-22T00:00:00Z");

  @BeforeEach
  void setUp() {
    inbox = mock(Inbox.class);
    orderRepository = mock(OrderRepository.class);
    stockPoolRepository = mock(StockPoolRepository.class);
    allocationService = mock(OrderAllocationCoordinator.class);
    eventPublisher = mock(ApplicationEventPublisher.class);
    clock = Clock.fixed(fixedNow, ZoneId.of("UTC"));

    usecase = new AllocateOrderUsecase(
        inbox,
        orderRepository,
        stockPoolRepository,
        allocationService,
        eventPublisher,
        clock
    );
  }

  @Test
  @DisplayName("當事件已被處理過時，不應執行任何邏輯")
  void shouldDoNothingWhenEventAlreadyProcessed() {
    // Given
    UUID eventId = UUID.randomUUID();
    given(inbox.claimIfNew(eventId)).willReturn(false);

    // When
    usecase.handle(anOrderPlacedEvent(eventId, UUID.randomUUID()));

    // Then
    then(inbox).should().claimIfNew(eventId);
    verifyNoInteractions(orderRepository, stockPoolRepository, allocationService, eventPublisher);
  }

  @Test
  @DisplayName("當訂單狀態不是 PENDING 時，不應執行分配")
  void shouldDoNothingWhenOrderIsNotPending() {
    // Given
    UUID eventId = UUID.randomUUID();
    Order order = aPendingOrder("SKU-1", 5);
    order.markAllocated(fixedNow); // 使其狀態變為 ALLOCATED

    given(inbox.claimIfNew(eventId)).willReturn(true);
    given(orderRepository.findById(order.getId())).willReturn(Optional.of(order));

    // When
    usecase.handle(anOrderPlacedEvent(eventId, order.getId()));

    // Then
    then(orderRepository).should().findById(order.getId());
    verifyNoInteractions(stockPoolRepository, allocationService, eventPublisher);
  }

  @Test
  @DisplayName("當找不到 Order 時，應由 use case 決定並拋出異常")
  void shouldThrowExceptionWhenOrderNotFound() {
    UUID eventId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    given(inbox.claimIfNew(eventId)).willReturn(true);
    given(orderRepository.findById(orderId)).willReturn(Optional.empty());

    assertThatThrownBy(() -> usecase.handle(anOrderPlacedEvent(eventId, orderId)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Order not found: " + orderId);

    verifyNoInteractions(stockPoolRepository, allocationService, eventPublisher);
  }

  @Test
  @DisplayName("當找不到 SKU 對應的 StockPool 時，應拋出異常")
  void shouldThrowExceptionWhenStockPoolNotFound() {
    // Given
    UUID eventId = UUID.randomUUID();
    Order order = aPendingOrder("SKU-1", 5);

    given(inbox.claimIfNew(eventId)).willReturn(true);
    given(orderRepository.findById(order.getId())).willReturn(Optional.of(order));
    given(stockPoolRepository.findBySku("SKU-1")).willReturn(Optional.empty());

    // When & Then
    assertThatThrownBy(() -> usecase.handle(anOrderPlacedEvent(eventId, order.getId())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("StockPool not found for SKU: SKU-1");
  }

  @Test
  @DisplayName("當庫存充足時，應成功分配訂單")
  void shouldAllocateSuccessfullyWhenStockIsEnough() {
    // Given
    UUID eventId = UUID.randomUUID();
    Order order = aPendingOrder("SKU-1", 5);
    StockPool stockPool = aStockPool("SKU-1", 10);

    given(inbox.claimIfNew(eventId)).willReturn(true);
    given(orderRepository.findById(order.getId())).willReturn(Optional.of(order));
    given(stockPoolRepository.findBySku(stockPool.getSku())).willReturn(Optional.of(stockPool));
    given(allocationService.allocateOrder(order, stockPool, fixedNow))
        .willReturn(Optional.of(order));

    // When
    usecase.handle(anOrderPlacedEvent(eventId, order.getId()));

    // Then
    then(allocationService).should().allocateOrder(order, stockPool, fixedNow);
  }

  @Test
  @DisplayName("當庫存不足時，應將訂單標記為欠單 (Backordered)")
  void shouldMarkAsBackorderedWhenStockIsInsufficient() {
    // Given
    UUID eventId = UUID.randomUUID();
    Order order = aPendingOrder("SKU-1", 5);
    StockPool stockPool = aStockPool("SKU-1", 2);

    given(inbox.claimIfNew(eventId)).willReturn(true);
    given(orderRepository.findById(order.getId())).willReturn(Optional.of(order));
    given(stockPoolRepository.findBySku(stockPool.getSku())).willReturn(Optional.of(stockPool));
    given(allocationService.allocateOrder(order, stockPool, fixedNow))
        .willReturn(Optional.empty());

    // When
    usecase.handle(anOrderPlacedEvent(eventId, order.getId()));

    // Then
    assertThat(order.getStatus())
        .as("訂單狀態應變更為 BACKORDERED")
        .isEqualTo(OrderStatus.BACKORDERED);

    then(orderRepository).should().save(order);
    ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
    then(eventPublisher).should(times(2)).publishEvent(eventCaptor.capture());
    assertThat(eventCaptor.getAllValues().get(0))
        .isEqualTo(new OrderBackordered(
            order.getId(), order.getSku(), order.getQuantity(), fixedNow));
    assertThat(eventCaptor.getAllValues().get(1))
        .isInstanceOfSatisfying(BackorderCreatedIntegrationEvent.class, event -> {
          assertThat(event.getEventId()).isNotNull();
          assertThat(event.getOrderId()).isEqualTo(order.getId());
          assertThat(event.getBackorderedSince()).isEqualTo(fixedNow);
        });
  }

  // --- Domain Language Helpers ---
  // 保留對象創建的 Helper 以維持測試數據的可讀性

  private OrderPlacedIntegrationEvent anOrderPlacedEvent(UUID eventId, UUID orderId) {
    return new OrderPlacedIntegrationEvent(eventId, orderId, "SKU-1", 5, fixedNow.minusSeconds(1));
  }

  private Order aPendingOrder(String sku, int quantity) {
    Order order = Order.place(UUID.randomUUID(), sku, quantity, fixedNow.minusSeconds(1));
    order.releaseDomainEvents();
    return order;
  }

  private StockPool aStockPool(String sku, int onHandQuantity) {
    return new StockPool(1L, sku, onHandQuantity, 0, 0L);
  }
}
