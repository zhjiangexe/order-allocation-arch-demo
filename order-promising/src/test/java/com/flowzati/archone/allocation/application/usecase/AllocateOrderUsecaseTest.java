package com.flowzati.archone.allocation.application.usecase;

import com.flowzati.archone.allocation.application.command.AllocateOrderCommand;
import com.flowzati.archone.allocation.application.coordinator.OrderAllocationCoordinator;
import com.flowzati.archone.allocation.domain.model.StockPool;
import com.flowzati.archone.allocation.domain.model.StockReservation;
import com.flowzati.archone.allocation.domain.repository.StockPoolRepository;
import com.flowzati.archone.common.inbox.InboxRepo;
import com.flowzati.archone.common.inbox.InboundCommand;
import com.flowzati.archone.common.inbox.MessageMetadata;
import com.flowzati.archone.ordering.domain.model.Order;
import com.flowzati.archone.ordering.domain.repository.OrderRepository;
import com.flowzati.archone.testsupport.OrderFixtures;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class AllocateOrderUsecaseTest {

  private final Instant fixedNow = Instant.parse("2026-07-22T00:00:00Z");

  private AllocateOrderUsecase usecase;
  private InboxRepo inboxRepo;
  private OrderRepository orderRepository;
  private StockPoolRepository stockPoolRepository;
  private OrderAllocationCoordinator allocationCoordinator;

  @BeforeEach
  void setUp() {
    inboxRepo = mock(InboxRepo.class);
    orderRepository = mock(OrderRepository.class);
    stockPoolRepository = mock(StockPoolRepository.class);
    allocationCoordinator = mock(OrderAllocationCoordinator.class);

    usecase = new AllocateOrderUsecase(
        inboxRepo,
        orderRepository,
        stockPoolRepository,
        allocationCoordinator,
        Clock.fixed(fixedNow, ZoneId.of("UTC"))
    );
  }

  @Test
  @DisplayName("當訊息已被處理過時，不應執行任何邏輯")
  void shouldDoNothingWhenMessageAlreadyProcessed() {
    UUID messageId = UUID.randomUUID();
    given(inboxRepo.claimIfNew(message(messageId))).willReturn(false);

    usecase.handle(inbound(new AllocateOrderCommand(UUID.randomUUID()), messageId));

    then(inboxRepo).should().claimIfNew(message(messageId));
    verifyNoInteractions(orderRepository, stockPoolRepository, allocationCoordinator);
  }

  @Test
  @DisplayName("當訂單狀態不是 PENDING 時，不應執行分配")
  void shouldDoNothingWhenOrderIsNotPending() {
    UUID messageId = UUID.randomUUID();
    Order order = pendingOrder("SKU-1", 5);
    order.markAllocated(fixedNow);
    given(inboxRepo.claimIfNew(message(messageId))).willReturn(true);
    given(orderRepository.findById(order.getId())).willReturn(Optional.of(order));

    usecase.handle(inbound(new AllocateOrderCommand(order.getId()), messageId));

    then(orderRepository).should().findById(order.getId());
    verifyNoInteractions(stockPoolRepository, allocationCoordinator);
  }

  @Test
  @DisplayName("當找不到 Order 時，應由 use case 決定並拋出異常")
  void shouldThrowExceptionWhenOrderNotFound() {
    UUID messageId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    given(inboxRepo.claimIfNew(message(messageId))).willReturn(true);
    given(orderRepository.findById(orderId)).willReturn(Optional.empty());

    assertThatThrownBy(() -> usecase.handle(inbound(new AllocateOrderCommand(orderId), messageId)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Order not found: " + orderId);

    verifyNoInteractions(stockPoolRepository, allocationCoordinator);
  }

  @Test
  @DisplayName("當找不到 SKU 對應的 StockPool 時，應拋出異常")
  void shouldThrowExceptionWhenStockPoolNotFound() {
    UUID messageId = UUID.randomUUID();
    Order order = pendingOrder("SKU-1", 5);
    given(inboxRepo.claimIfNew(message(messageId))).willReturn(true);
    given(orderRepository.findById(order.getId())).willReturn(Optional.of(order));
    given(stockPoolRepository.findBySku("SKU-1")).willReturn(Optional.empty());

    assertThatThrownBy(() -> usecase.handle(inbound(new AllocateOrderCommand(order.getId()), messageId)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("StockPool not found for SKU: SKU-1");
  }

  @Test
  @DisplayName("當庫存充足時，應成功分配訂單")
  void shouldAllocateSuccessfullyWhenStockIsEnough() {
    UUID messageId = UUID.randomUUID();
    Order order = pendingOrder("SKU-1", 5);
    StockPool stockPool = stockPool("SKU-1", 10);
    given(inboxRepo.claimIfNew(message(messageId))).willReturn(true);
    given(orderRepository.findById(order.getId())).willReturn(Optional.of(order));
    given(stockPoolRepository.findBySku(stockPool.getSku())).willReturn(Optional.of(stockPool));
    given(allocationCoordinator.allocateOrder(order, stockPool, fixedNow))
        .willReturn(Optional.of(reservation(order, stockPool)));

    usecase.handle(inbound(new AllocateOrderCommand(order.getId()), messageId));

    then(allocationCoordinator).should().allocateOrder(order, stockPool, fixedNow);
  }

  @Test
  @DisplayName("當庫存不足時，應委派 Coordinator 標記欠單")
  void shouldBackorderWhenStockIsInsufficient() {
    UUID messageId = UUID.randomUUID();
    Order order = pendingOrder("SKU-1", 5);
    StockPool stockPool = stockPool("SKU-1", 2);
    given(inboxRepo.claimIfNew(message(messageId))).willReturn(true);
    given(orderRepository.findById(order.getId())).willReturn(Optional.of(order));
    given(stockPoolRepository.findBySku(stockPool.getSku())).willReturn(Optional.of(stockPool));
    given(allocationCoordinator.allocateOrder(order, stockPool, fixedNow))
        .willReturn(Optional.empty());

    usecase.handle(inbound(new AllocateOrderCommand(order.getId()), messageId));

    then(allocationCoordinator).should().backorderOrder(order, fixedNow);
  }

  private Order pendingOrder(String sku, int quantity) {
    Order order = OrderFixtures.pendingOrder(UUID.randomUUID(), sku, quantity, fixedNow.minusSeconds(1));
    order.releaseDomainEvents();
    return order;
  }

  private StockPool stockPool(String sku, int onHandQuantity) {
    return new StockPool(java.util.UUID.randomUUID(), sku, onHandQuantity, 0, 0L);
  }

  private StockReservation reservation(Order order, StockPool stockPool) {
    return StockReservation.create(
        UUID.randomUUID(), order.getId(), stockPool.getId(), order.getQuantity(), fixedNow);
  }

  private MessageMetadata message(UUID eventId) {
    return new MessageMetadata(eventId, "OrderPlacedIntegrationEvent");
  }

  private InboundCommand<AllocateOrderCommand> inbound(AllocateOrderCommand command, UUID eventId) {
    return new InboundCommand<>(command, message(eventId));
  }
}
