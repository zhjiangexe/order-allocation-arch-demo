package com.flowzati.archone.allocation.application.usecase;

import com.flowzati.archone.allocation.application.command.AllocateOrderCommand;
import com.flowzati.archone.allocation.application.coordinator.OrderAllocationCoordinator;
import com.flowzati.archone.allocation.domain.model.StockPool;
import com.flowzati.archone.allocation.domain.model.StockFixtures;
import com.flowzati.archone.allocation.domain.service.AllocationOutcome;
import com.flowzati.archone.allocation.domain.repository.StockPoolRepository;
import com.flowzati.archone.common.inbox.InboxRepo;
import com.flowzati.archone.common.time.BusinessCalendar;
import com.flowzati.archone.common.inbox.InboundCommand;
import com.flowzati.archone.common.inbox.MessageMetadata;
import com.flowzati.archone.ordering.domain.model.Order;
import com.flowzati.archone.ordering.domain.repository.OrderRepository;
import com.flowzati.archone.testsupport.OrderFixtures;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
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

  /** 台北 2026-07-22 早上 7 點——UTC 此刻還停在 07-21，剛好落在會出錯的那八小時內。 */
  private final Instant fixedNow = Instant.parse("2026-07-21T23:00:00Z");
  private static final LocalDate TODAY_IN_TAIPEI = LocalDate.of(2026, 7, 22);

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
        Clock.fixed(fixedNow, ZoneId.of("UTC")),
        new BusinessCalendar(Clock.fixed(fixedNow, ZoneId.of("UTC")), "Asia/Taipei")
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
  @DisplayName("一批可售的都沒有時應掛帳，不得丟例外")
  void shouldBackorderRatherThanFailWhenThereIsNoAllocatableStock() {
    UUID messageId = UUID.randomUUID();
    Order order = pendingOrder("SKU-1", 5);
    given(inboxRepo.claimIfNew(message(messageId))).willReturn(true);
    given(orderRepository.findById(order.getId())).willReturn(Optional.of(order));
    givenAllocatableBatches(List.of());
    given(allocationCoordinator.allocateOrder(order, List.of(), fixedNow))
        .willReturn(AllocationOutcome.NO_ALLOCATABLE_STOCK);

    usecase.handle(inbound(new AllocateOrderCommand(order.getId()), messageId));

    // 缺貨是正常結果，不是訊息處理失敗。丟例外的話每一次缺貨都會走進重試與 DLT。
    then(allocationCoordinator).should().backorderOrder(order, fixedNow);
  }

  @Test
  @DisplayName("應以訂單的貨主、倉與今天去查可售批")
  void shouldQueryAllocatableBatchesByOwnerNodeAndToday() {
    UUID messageId = UUID.randomUUID();
    Order order = pendingOrder("SKU-1", 5);
    StockPool batch = stockPool("SKU-1", 10);
    given(inboxRepo.claimIfNew(message(messageId))).willReturn(true);
    given(orderRepository.findById(order.getId())).willReturn(Optional.of(order));
    givenAllocatableBatches(List.of(batch));
    given(allocationCoordinator.allocateOrder(order, List.of(batch), fixedNow))
        .willReturn(AllocationOutcome.ALLOCATED);

    usecase.handle(inbound(new AllocateOrderCommand(order.getId()), messageId));

    // 過期篩選與 FEFO 排序都在資料庫做——批數只會隨時間成長，把不可售的載進記憶體只為了
    // 丟掉是錯的方向。
    then(stockPoolRepository).should().findAllocatableBatchesInFefoOrder(
        OrderFixtures.OWNER_ID, OrderFixtures.NODE_ID, "SKU-1",
        TODAY_IN_TAIPEI);
  }

  @Test
  @DisplayName("當庫存充足時，應成功分配訂單")
  void shouldAllocateSuccessfullyWhenStockIsEnough() {
    UUID messageId = UUID.randomUUID();
    Order order = pendingOrder("SKU-1", 5);
    StockPool batch = stockPool("SKU-1", 10);
    given(inboxRepo.claimIfNew(message(messageId))).willReturn(true);
    given(orderRepository.findById(order.getId())).willReturn(Optional.of(order));
    givenAllocatableBatches(List.of(batch));
    given(allocationCoordinator.allocateOrder(order, List.of(batch), fixedNow))
        .willReturn(AllocationOutcome.ALLOCATED);

    usecase.handle(inbound(new AllocateOrderCommand(order.getId()), messageId));

    then(allocationCoordinator).should().allocateOrder(order, List.of(batch), fixedNow);
    then(allocationCoordinator).should(org.mockito.Mockito.never())
        .backorderOrder(order, fixedNow);
  }

  @Test
  @DisplayName("當庫存不足時，應委派 Coordinator 標記欠單")
  void shouldBackorderWhenStockIsInsufficient() {
    UUID messageId = UUID.randomUUID();
    Order order = pendingOrder("SKU-1", 5);
    StockPool batch = stockPool("SKU-1", 2);
    given(inboxRepo.claimIfNew(message(messageId))).willReturn(true);
    given(orderRepository.findById(order.getId())).willReturn(Optional.of(order));
    givenAllocatableBatches(List.of(batch));
    given(allocationCoordinator.allocateOrder(order, List.of(batch), fixedNow))
        .willReturn(AllocationOutcome.INSUFFICIENT_ATP);

    usecase.handle(inbound(new AllocateOrderCommand(order.getId()), messageId));

    then(allocationCoordinator).should().backorderOrder(order, fixedNow);
  }

  private void givenAllocatableBatches(List<StockPool> batches) {
    given(stockPoolRepository.findAllocatableBatchesInFefoOrder(
        OrderFixtures.OWNER_ID, OrderFixtures.NODE_ID, "SKU-1",
        TODAY_IN_TAIPEI)).willReturn(batches);
  }

  private Order pendingOrder(String sku, int quantity) {
    Order order = OrderFixtures.pendingOrder(UUID.randomUUID(), sku, quantity, fixedNow.minusSeconds(1));
    order.releaseDomainEvents();
    return order;
  }

  private StockPool stockPool(String sku, int onHandQuantity) {
    return StockFixtures.unexpiredBatch(sku, onHandQuantity, 0);
  }

  private MessageMetadata message(UUID eventId) {
    return new MessageMetadata(eventId, "OrderPlacedIntegrationEvent");
  }

  private InboundCommand<AllocateOrderCommand> inbound(AllocateOrderCommand command, UUID eventId) {
    return new InboundCommand<>(command, message(eventId));
  }
}
