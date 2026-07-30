package com.flowzati.archone.allocation.application.usecase;

import com.flowzati.archone.allocation.application.command.ReplenishStockCommand;
import com.flowzati.archone.allocation.application.coordinator.OrderAllocationCoordinator;
import com.flowzati.archone.allocation.domain.event.BackorderWakeContinuationRequired;
import com.flowzati.archone.allocation.application.event.StockReplenishedIntegrationEvent;
import com.flowzati.archone.allocation.domain.model.ReservationStatus;
import com.flowzati.archone.allocation.domain.model.StockPool;
import com.flowzati.archone.allocation.domain.model.StockFixtures;
import com.flowzati.archone.allocation.domain.model.StockReservation;
import com.flowzati.archone.allocation.domain.repository.StockPoolRepository;
import com.flowzati.archone.allocation.domain.repository.StockReservationRepository;
import com.flowzati.archone.allocation.domain.service.AllocationService;
import com.flowzati.archone.common.inbox.InboxRepo;
import com.flowzati.archone.common.inbox.InboundCommand;
import com.flowzati.archone.common.inbox.MessageMetadata;
import com.flowzati.archone.common.time.BusinessCalendar;
import com.flowzati.archone.ordering.domain.model.Order;
import com.flowzati.archone.ordering.domain.model.OrderStatus;
import com.flowzati.archone.ordering.domain.event.OrderAllocated;
import com.flowzati.archone.ordering.domain.repository.OrderRepository;
import com.flowzati.archone.testsupport.OrderFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ReplenishmentUsecaseTest {

  private static final String SKU = "SKU-1";
  // 直接用 fixture 的常數：這兩個日期是五維鍵的一部分，stub 的 findByIdentity 與 fixture
  // 造出來的批必須是同一組值，各寫一份遲早對不上。
  private static final LocalDate IN_DATE = StockFixtures.ARRIVED_ON;
  private static final LocalDate EXPIRY_DATE = StockFixtures.EXPIRES_ON;
  private static final int WAKE_LIMIT = 200;

  /** 台北 2026-07-22 早上 7 點——UTC 此刻還停在 07-21，剛好落在會出錯的那八小時內。 */
  private final Instant fixedNow = Instant.parse("2026-07-21T23:00:00Z");
  private final LocalDate today = LocalDate.of(2026, 7, 22);

  private StockPoolRepository stockPoolRepository;
  private OrderRepository orderRepository;
  private StockReservationRepository stockReservationRepository;
  private InboxRepo inboxRepo;
  private ApplicationEventPublisher eventPublisher;
  private ReplenishmentUsecase replenishmentUsecase;

  @BeforeEach
  void setUp() {
    stockPoolRepository = mock(StockPoolRepository.class);
    orderRepository = mock(OrderRepository.class);
    stockReservationRepository = mock(StockReservationRepository.class);
    eventPublisher = mock(ApplicationEventPublisher.class);
    inboxRepo = mock(InboxRepo.class);

    OrderAllocationCoordinator coordinator = new OrderAllocationCoordinator(
        new AllocationService(),
        stockPoolRepository,
        orderRepository,
        stockReservationRepository,
        eventPublisher
    );

    replenishmentUsecase = new ReplenishmentUsecase(
        Clock.fixed(fixedNow, ZoneId.of("UTC")),
        new BusinessCalendar(Clock.fixed(fixedNow, ZoneId.of("UTC")), "Asia/Taipei"),
        inboxRepo,
        orderRepository,
        stockPoolRepository,
        coordinator,
        eventPublisher,
        WAKE_LIMIT
    );
  }

  @Test
  @DisplayName("五維鍵命中既有批時應加到那一列，不另開新列")
  void shouldAddToTheExistingBatchWhenAllFiveDimensionsMatch() {
    UUID eventId = UUID.randomUUID();
    StockPool existing = StockFixtures.unexpiredBatch(SKU, 4, 0);
    when(inboxRepo.claimIfNew(message(eventId))).thenReturn(true);
    when(stockPoolRepository.findByIdentity(
        OrderFixtures.OWNER_ID, OrderFixtures.NODE_ID, SKU, IN_DATE, EXPIRY_DATE))
        .thenReturn(Optional.of(existing));
    givenAllocatableBatches(List.of(existing));
    givenBackorders(List.of());

    replenishmentUsecase.handle(inbound(event(eventId, 10)));

    assertThat(existing.getOnHandQuantity()).isEqualTo(14);
    verify(stockPoolRepository).save(existing);
  }

  @Test
  @DisplayName("五維鍵沒有命中時應新開一列，而不是併進最像的那一批")
  void shouldOpenANewBatchWhenNoIdentityMatches() {
    UUID eventId = UUID.randomUUID();
    when(inboxRepo.claimIfNew(message(eventId))).thenReturn(true);
    when(stockPoolRepository.findByIdentity(
        OrderFixtures.OWNER_ID, OrderFixtures.NODE_ID, SKU, IN_DATE, EXPIRY_DATE))
        .thenReturn(Optional.empty());
    givenAllocatableBatches(List.of());

    replenishmentUsecase.handle(inbound(event(eventId, 10)));

    // 沒有「差不多就併進去」的規則，因為根本沒有規則要定——五個維度全等才是同一批。
    ArgumentCaptor<StockPool> captor = ArgumentCaptor.forClass(StockPool.class);
    verify(stockPoolRepository).save(captor.capture());
    StockPool created = captor.getValue();
    assertThat(created.getOwnerId()).isEqualTo(OrderFixtures.OWNER_ID);
    assertThat(created.getNodeId()).isEqualTo(OrderFixtures.NODE_ID);
    assertThat(created.getSkuCode()).isEqualTo(SKU);
    assertThat(created.getInDate()).isEqualTo(IN_DATE);
    assertThat(created.getExpiryDate()).isEqualTo(EXPIRY_DATE);
    assertThat(created.getOnHandQuantity()).isEqualTo(10);
    assertThat(created.getReservedQuantity()).isZero();
  }

  @Test
  @DisplayName("當收到新的補充事件時，應補充庫存並分配等待中的訂單")
  void shouldReplenishAndAllocateWhenEventIsNew() {
    UUID eventId = UUID.randomUUID();
    StockPool batch = StockFixtures.unexpiredBatch(SKU, 0, 0);
    when(inboxRepo.claimIfNew(message(eventId))).thenReturn(true);
    givenIdentityMatch(batch);
    Order backorderedOrder = backorderedOrder(5);
    givenBackorders(List.of(backorderedOrder));
    // 喚醒讀到的是同一個批物件——upsert 先把數量加上去，喚醒才查。
    givenAllocatableBatches(List.of(batch));

    replenishmentUsecase.handle(inbound(event(eventId, 10)));

    verify(inboxRepo).claimIfNew(message(eventId));
    verify(orderRepository).save(backorderedOrder);
    ArgumentCaptor<StockReservation> reservationCaptor =
        ArgumentCaptor.forClass(StockReservation.class);
    verify(stockReservationRepository).save(reservationCaptor.capture());
    verify(eventPublisher, atLeastOnce()).publishEvent(any(OrderAllocated.class));

    assertThat(backorderedOrder.getStatus()).isEqualTo(OrderStatus.ALLOCATED);
    assertThat(backorderedOrder.getAllocatedAt()).isEqualTo(fixedNow);
    assertThat(batch.availableToPromise()).isEqualTo(5); // 0 + 10 - 5 = 5
    assertThat(reservationCaptor.getValue()).satisfies(reservation -> {
      assertThat(reservation.getOrderLineId())
          .isEqualTo(backorderedOrder.getLines().get(0).getId());
      assertThat(reservation.getStockPoolId()).isEqualTo(batch.getId());
      assertThat(reservation.getQuantity()).isEqualTo(5);
      assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.ACTIVE);
      assertThat(reservation.getReservedAt()).isEqualTo(fixedNow);
    });
  }

  @Test
  @DisplayName("當補充庫存不足以分配所有訂單時，應按順序部分分配")
  void shouldAllocatePartiallyWhenReplenishedStockIsLimited() {
    UUID eventId = UUID.randomUUID();
    StockPool batch = StockFixtures.unexpiredBatch(SKU, 0, 0);
    when(inboxRepo.claimIfNew(message(eventId))).thenReturn(true);
    givenIdentityMatch(batch);
    Order first = backorderedOrder(3);
    Order blocked = backorderedOrder(4);
    givenBackorders(List.of(first, blocked));
    givenAllocatableBatches(List.of(batch));

    replenishmentUsecase.handle(inbound(event(eventId, 5)));

    // 0 + 5 = 5；first(3) 配到，剩 2；blocked(4) 配不到，且 FIFO 之下就此停住。
    assertThat(first.getStatus()).isEqualTo(OrderStatus.ALLOCATED);
    assertThat(blocked.getStatus()).isEqualTo(OrderStatus.BACKORDERED);
    assertThat(batch.availableToPromise()).isEqualTo(2);

    verify(orderRepository).save(first);
    verify(stockReservationRepository).save(any(StockReservation.class));
    verify(orderRepository, never()).save(blocked);
  }

  @Test
  @DisplayName("當沒有待處理訂單時，應僅更新庫存而不進行分配")
  void shouldOnlyReplenishWhenNoPendingOrders() {
    UUID eventId = UUID.randomUUID();
    StockPool batch = StockFixtures.unexpiredBatch(SKU, 0, 0);
    when(inboxRepo.claimIfNew(message(eventId))).thenReturn(true);
    givenIdentityMatch(batch);
    givenAllocatableBatches(List.of(batch));
    givenBackorders(List.of());

    replenishmentUsecase.handle(inbound(event(eventId, 10)));

    verify(stockPoolRepository).save(batch);
    assertThat(batch.availableToPromise()).isEqualTo(10);
    verify(orderRepository, never()).save(any());
    verify(stockReservationRepository, never()).save(any());
    verify(eventPublisher, never()).publishEvent(any());
  }

  @Test
  @DisplayName("喚醒張數達到上限時應發一則續做的領域事件，帶著爭用群組的三個維度")
  void shouldRequestContinuationWhenTheWakeLimitIsReached() {
    UUID eventId = UUID.randomUUID();
    StockPool batch = StockFixtures.unexpiredBatch(SKU, 0, 0);
    when(inboxRepo.claimIfNew(message(eventId))).thenReturn(true);
    givenIdentityMatch(batch);
    givenAllocatableBatches(List.of(batch));
    givenBackorders(IntStream.range(0, WAKE_LIMIT).mapToObj(i -> backorderedOrder(1)).toList());

    replenishmentUsecase.handle(inbound(event(eventId, WAKE_LIMIT)));

    // usecase 只發領域事實。**它不該知道 outbox 存在**——譯成對外事件、決定 topic 與
    // partition key 是 translator 的事（見 DomainEventTranslatorTest）。
    verify(eventPublisher).publishEvent(new BackorderWakeContinuationRequired(
        OrderFixtures.OWNER_ID, OrderFixtures.NODE_ID, SKU, fixedNow));
  }

  @Test
  @DisplayName("喚醒張數未達上限時不應續做——那代表佇列已清空或被 head-of-line 卡住")
  void shouldNotRequestContinuationWhenTheQueueIsShorterThanTheLimit() {
    UUID eventId = UUID.randomUUID();
    StockPool batch = StockFixtures.unexpiredBatch(SKU, 0, 0);
    when(inboxRepo.claimIfNew(message(eventId))).thenReturn(true);
    givenIdentityMatch(batch);
    givenAllocatableBatches(List.of(batch));
    // 佇列首張要 100 件、庫存只有 10：卡住了，但再送一次結果完全相同。以「還有沒有配到的
    // 單」當續做條件的話，這張單會讓續做無限循環。
    givenBackorders(List.of(backorderedOrder(100)));

    replenishmentUsecase.handle(inbound(event(eventId, 10)));

    verify(eventPublisher, never()).publishEvent(any(BackorderWakeContinuationRequired.class));
  }

  @Test
  @DisplayName("續做喚醒不得再加庫存，只把佇列接著餵完")
  void shouldNotReplenishAgainWhenHandlingAContinuation() {
    UUID eventId = UUID.randomUUID();
    StockPool batch = StockFixtures.unexpiredBatch(SKU, 10, 0);
    when(inboxRepo.claimIfNew(message(eventId))).thenReturn(true);
    givenAllocatableBatches(List.of(batch));
    Order order = backorderedOrder(4);
    givenBackorders(List.of(order));

    replenishmentUsecase.handleWake(new InboundCommand<>(
        new com.flowzati.archone.allocation.application.command.WakeBackordersCommand(
            OrderFixtures.OWNER_ID, OrderFixtures.NODE_ID, SKU),
        message(eventId)));

    assertThat(batch.getOnHandQuantity()).isEqualTo(10);
    assertThat(order.getStatus()).isEqualTo(OrderStatus.ALLOCATED);
    verify(stockPoolRepository, never()).findByIdentity(any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("當事件已被處理過時，不應重複執行補充邏輯")
  void shouldDoNothingWhenEventIsAlreadyProcessed() {
    UUID eventId = UUID.randomUUID();
    when(inboxRepo.claimIfNew(message(eventId))).thenReturn(false);

    replenishmentUsecase.handle(inbound(event(eventId, 10)));

    verify(inboxRepo).claimIfNew(message(eventId));
    verifyNoInteractions(
        stockPoolRepository, orderRepository, stockReservationRepository, eventPublisher);
  }

  @Test
  @DisplayName("補進來的批已過期時不應喚醒任何訂單")
  void shouldNotWakeAnyOrderWhenTheReplenishedBatchIsAlreadyExpired() {
    UUID eventId = UUID.randomUUID();
    when(inboxRepo.claimIfNew(message(eventId))).thenReturn(true);
    when(stockPoolRepository.findByIdentity(
        OrderFixtures.OWNER_ID, OrderFixtures.NODE_ID, SKU, IN_DATE, EXPIRY_DATE))
        .thenReturn(Optional.empty());
    // 可售批查詢在資料庫就把過期的濾掉了，因此這裡回空。
    givenAllocatableBatches(List.of());

    replenishmentUsecase.handle(inbound(event(eventId, 10)));

    verify(stockPoolRepository).save(any(StockPool.class));
    verifyNoInteractions(orderRepository, stockReservationRepository, eventPublisher);
  }

  private void givenIdentityMatch(StockPool batch) {
    when(stockPoolRepository.findByIdentity(
        OrderFixtures.OWNER_ID, OrderFixtures.NODE_ID, SKU, IN_DATE, EXPIRY_DATE))
        .thenReturn(Optional.of(batch));
  }

  private void givenAllocatableBatches(List<StockPool> batches) {
    when(stockPoolRepository.findAllocatableBatchesInFefoOrder(
        OrderFixtures.OWNER_ID, OrderFixtures.NODE_ID, SKU, today)).thenReturn(batches);
  }

  private void givenBackorders(List<Order> orders) {
    when(orderRepository.findBackordersBySkuInFifoOrder(
        OrderFixtures.OWNER_ID, SKU, WAKE_LIMIT)).thenReturn(orders);
  }

  private Order backorderedOrder(int quantity) {
    Order order =
        OrderFixtures.pendingOrder(UUID.randomUUID(), SKU, quantity, fixedNow.minusSeconds(2));
    order.markBackOrdered(fixedNow.minusSeconds(1));
    order.releaseDomainEvents();
    return order;
  }

  private StockReplenishedIntegrationEvent event(UUID eventId, int quantity) {
    return new StockReplenishedIntegrationEvent(
        eventId, OrderFixtures.OWNER_ID, OrderFixtures.NODE_ID, SKU, IN_DATE, EXPIRY_DATE,
        quantity);
  }

  private MessageMetadata message(UUID eventId) {
    return new MessageMetadata(eventId, StockReplenishedIntegrationEvent.class.getSimpleName());
  }

  private ReplenishStockCommand command(StockReplenishedIntegrationEvent event) {
    return new ReplenishStockCommand(
        event.getOwnerId(), event.getNodeId(), event.getSku(),
        event.getInDate(), event.getExpiryDate(), event.getQuantity());
  }

  private InboundCommand<ReplenishStockCommand> inbound(StockReplenishedIntegrationEvent event) {
    return new InboundCommand<>(command(event), message(event.getEventId()));
  }
}
