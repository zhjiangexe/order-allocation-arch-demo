package com.flowzati.archone.allocation.application.usecase;

import com.flowzati.archone.allocation.application.command.ReplenishStockCommand;
import com.flowzati.archone.allocation.application.coordinator.OrderAllocationCoordinator;
import com.flowzati.archone.allocation.domain.event.BackorderWakeContinuationRequired;
import com.flowzati.archone.allocation.application.event.StockReplenishedIntegrationEvent;
import com.flowzati.archone.allocation.application.query.WaitingDemandFinder;
import com.flowzati.archone.allocation.domain.model.MoveState;
import com.flowzati.archone.allocation.domain.model.StockMove;
import com.flowzati.archone.allocation.domain.model.StockMoveLine;
import com.flowzati.archone.allocation.domain.model.StockPool;
import com.flowzati.archone.allocation.domain.model.StockFixtures;
import com.flowzati.archone.allocation.domain.repository.StockMoveRepository;
import com.flowzati.archone.allocation.domain.repository.StockPoolRepository;
import com.flowzati.archone.allocation.domain.service.AllocationService;
import com.flowzati.archone.common.inbox.InboxRepo;
import com.flowzati.archone.common.inbox.InboundCommand;
import com.flowzati.archone.common.inbox.MessageMetadata;
import com.flowzati.archone.common.time.BusinessCalendar;
import com.flowzati.archone.allocation.domain.event.OrderAllocationCompleted;
import com.flowzati.archone.allocation.domain.model.Demand;
import com.flowzati.archone.common.IdGenerator;
import com.flowzati.archone.ordering.domain.model.OrderStatus;
import com.flowzati.archone.ordering.domain.event.OrderAllocated;
import com.flowzati.archone.testsupport.DemandFixtures;
import com.flowzati.archone.testsupport.MovementFixtures;
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
import java.util.ArrayList;
import java.util.Collection;
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
  private WaitingDemandFinder waitingDemandFinder;
  private StockMoveRepository stockMoveRepository;
  private InboxRepo inboxRepo;
  private ApplicationEventPublisher eventPublisher;
  private ReplenishmentUsecase replenishmentUsecase;

  @BeforeEach
  void setUp() {
    stockPoolRepository = mock(StockPoolRepository.class);
    waitingDemandFinder = mock(WaitingDemandFinder.class);
    stockMoveRepository = mock(StockMoveRepository.class);
    eventPublisher = mock(ApplicationEventPublisher.class);
    inboxRepo = mock(InboxRepo.class);

    OrderAllocationCoordinator coordinator = new OrderAllocationCoordinator(
        new AllocationService(),
        stockPoolRepository,
        stockMoveRepository,
        eventPublisher
    );

    replenishmentUsecase = new ReplenishmentUsecase(
        Clock.fixed(fixedNow, ZoneId.of("UTC")),
        new BusinessCalendar(Clock.fixed(fixedNow, ZoneId.of("UTC")), "Asia/Taipei"),
        inboxRepo,
        waitingDemandFinder,
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
        OrderFixtures.OWNER_ID, OrderFixtures.LOCATION_ID, SKU, IN_DATE, EXPIRY_DATE))
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
        OrderFixtures.OWNER_ID, OrderFixtures.LOCATION_ID, SKU, IN_DATE, EXPIRY_DATE))
        .thenReturn(Optional.empty());
    givenAllocatableBatches(List.of());

    replenishmentUsecase.handle(inbound(event(eventId, 10)));

    // 沒有「差不多就併進去」的規則，因為根本沒有規則要定——五個維度全等才是同一批。
    ArgumentCaptor<StockPool> captor = ArgumentCaptor.forClass(StockPool.class);
    verify(stockPoolRepository).save(captor.capture());
    StockPool created = captor.getValue();
    assertThat(created.getOwnerId()).isEqualTo(OrderFixtures.OWNER_ID);
    assertThat(created.getLocationId()).isEqualTo(OrderFixtures.LOCATION_ID);
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
    Demand backorderedDemand = backorderedDemand(5);
    givenBackorders(List.of(backorderedDemand));
    // 喚醒讀到的是同一個批物件——upsert 先把數量加上去，喚醒才查。
    givenAllocatableBatches(List.of(batch));

    replenishmentUsecase.handle(inbound(event(eventId, 10)));

    verify(inboxRepo).claimIfNew(message(eventId));
    verify(eventPublisher).publishEvent(allocationCompletedFor(backorderedDemand));

    assertThat(batch.availableToPromise()).isEqualTo(5); // 0 + 10 - 5 = 5

    // 喚醒不新建搬運，只把等著的那一段轉成已鎖定——它在收單時就存在了。
    StockMove woken = savedMoves().getFirst();
    assertThat(woken.getState()).isEqualTo(MoveState.ASSIGNED);
    assertThat(woken.getAssignedAt()).isEqualTo(fixedNow);
    assertThat(woken.getOrderLineId())
        .isEqualTo(backorderedDemand.lines().getFirst().orderLineId());

    StockMoveLine line = savedLines().getFirst();
    assertThat(line.moveId()).isEqualTo(woken.getId());
    assertThat(line.stockPoolId()).isEqualTo(batch.getId());
    assertThat(line.quantity()).isEqualTo(5);
  }

  @Test
  @DisplayName("當補充庫存不足以分配所有訂單時，應按順序部分分配")
  void shouldAllocatePartiallyWhenReplenishedStockIsLimited() {
    UUID eventId = UUID.randomUUID();
    StockPool batch = StockFixtures.unexpiredBatch(SKU, 0, 0);
    when(inboxRepo.claimIfNew(message(eventId))).thenReturn(true);
    givenIdentityMatch(batch);
    Demand first = backorderedDemand(3);
    Demand blocked = backorderedDemand(4);
    givenBackorders(List.of(first, blocked));
    givenAllocatableBatches(List.of(batch));

    replenishmentUsecase.handle(inbound(event(eventId, 5)));

    // 0 + 5 = 5；first(3) 配到，剩 2；blocked(4) 配不到，且 FIFO 之下就此停住。
    assertThat(batch.availableToPromise()).isEqualTo(2);

    // 「被配到」的觀察點是事件，不是訂單狀態——配貨已經不寫訂單了。blocked 沒有事件，
    // 正是 head-of-line blocking 的內容：它沒有被跳過去換後面配得到的單。
    verify(eventPublisher).publishEvent(allocationCompletedFor(first));
    verify(eventPublisher, never()).publishEvent(allocationCompletedFor(blocked));
    // 只有配到的那一張留下明細——被卡住的那張連一條都沒有。
    assertThat(savedLines()).hasSize(1);
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
    verify(eventPublisher, never()).publishEvent(any(OrderAllocationCompleted.class));
    verify(stockMoveRepository, never()).saveAll(any());
    verify(stockMoveRepository, never()).saveLines(any());
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
    givenBackorders(IntStream.range(0, WAKE_LIMIT).mapToObj(i -> backorderedDemand(1)).toList());

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
    givenBackorders(List.of(backorderedDemand(100)));

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
    Demand queued = backorderedDemand(4);
    givenBackorders(List.of(queued));

    replenishmentUsecase.handleWake(new InboundCommand<>(
        new com.flowzati.archone.allocation.application.command.WakeBackordersCommand(
            OrderFixtures.OWNER_ID, OrderFixtures.NODE_ID, OrderFixtures.LOCATION_ID, SKU),
        message(eventId)));

    assertThat(batch.getOnHandQuantity()).isEqualTo(10);
    // 佇列確實被餵完了——續做只是接著配，不是重新補一次貨。
    verify(eventPublisher).publishEvent(allocationCompletedFor(queued));
    verify(stockPoolRepository, never()).findByIdentity(any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("當事件已被處理過時，不應重複執行補充邏輯")
  void shouldDoNothingWhenEventIsAlreadyProcessed() {
    UUID eventId = UUID.randomUUID();
    when(inboxRepo.claimIfNew(message(eventId))).thenReturn(false);

    replenishmentUsecase.handle(inbound(event(eventId, 10)));

    verify(inboxRepo).claimIfNew(message(eventId));
    verifyNoInteractions(stockPoolRepository, stockMoveRepository, eventPublisher);
  }

  @Test
  @DisplayName("補進來的批已過期時不應喚醒任何訂單")
  void shouldNotWakeAnyOrderWhenTheReplenishedBatchIsAlreadyExpired() {
    UUID eventId = UUID.randomUUID();
    when(inboxRepo.claimIfNew(message(eventId))).thenReturn(true);
    when(stockPoolRepository.findByIdentity(
        OrderFixtures.OWNER_ID, OrderFixtures.LOCATION_ID, SKU, IN_DATE, EXPIRY_DATE))
        .thenReturn(Optional.empty());
    // 可售批查詢在資料庫就把過期的濾掉了，因此這裡回空。
    givenAllocatableBatches(List.of());

    replenishmentUsecase.handle(inbound(event(eventId, 10)));

    verify(stockPoolRepository).save(any(StockPool.class));
    verifyNoInteractions(waitingDemandFinder, stockMoveRepository, eventPublisher);
  }

  private void givenIdentityMatch(StockPool batch) {
    when(stockPoolRepository.findByIdentity(
        OrderFixtures.OWNER_ID, OrderFixtures.LOCATION_ID, SKU, IN_DATE, EXPIRY_DATE))
        .thenReturn(Optional.of(batch));
  }

  /**
   * 兩支查詢一起 stub：補的這個 SKU 有沒有量可配（守門），以及候選單涉及的所有 SKU 的批
   * （真正拿去配的）。這些測試的候選單都只要 {@code SKU}，所以兩者的內容相同。
   */
  private void givenAllocatableBatches(List<StockPool> batches) {
    when(stockPoolRepository.findAllocatableBatchesInFefoOrder(
        OrderFixtures.OWNER_ID, OrderFixtures.LOCATION_ID, SKU, today)).thenReturn(batches);
    when(stockPoolRepository.findAllocatableBatchesBySku(
        OrderFixtures.OWNER_ID, OrderFixtures.LOCATION_ID, java.util.Set.of(SKU), today))
        .thenReturn(java.util.Map.of(SKU, batches));
  }

  /**
   * 佇列裡的單，以及它們各自那一段還在等貨的搬運。
   *
   * <p>兩者要一起 stub：佇列本來就是**從搬運投影出來的**，配貨接著會去找同一批搬運把它們轉成
   * 已鎖定。只給前者的話，配貨會在找不到搬運時拋錯——而那個錯是對的。
   */
  private void givenBackorders(List<Demand> demands) {
    when(waitingDemandFinder.findWaiting(
        OrderFixtures.OWNER_ID, OrderFixtures.LOCATION_ID, SKU, WAKE_LIMIT)).thenReturn(demands);

    List<StockMove> moves = new ArrayList<>();
    demands.forEach(demand -> {
      UUID pickingId = IdGenerator.nextId();
      demand.lines().forEach(line -> moves.add(MovementFixtures.waitingMove(
          pickingId, line.skuCode(), line.orderLineId(), line.quantity(),
          fixedNow.minusSeconds(2))));
    });
    when(stockMoveRepository.findByOrderLineIds(any())).thenAnswer(invocation -> {
      java.util.Collection<?> ids = invocation.getArgument(0);
      return moves.stream().filter(move -> ids.contains(move.getOrderLineId())).toList();
    });
  }

  @SuppressWarnings("unchecked")
  private List<StockMove> savedMoves() {
    ArgumentCaptor<Collection<StockMove>> captor = ArgumentCaptor.forClass(Collection.class);
    verify(stockMoveRepository).saveAll(captor.capture());
    return List.copyOf(captor.getValue());
  }

  @SuppressWarnings("unchecked")
  private List<StockMoveLine> savedLines() {
    ArgumentCaptor<Collection<StockMoveLine>> captor = ArgumentCaptor.forClass(Collection.class);
    verify(stockMoveRepository).saveLines(captor.capture());
    return List.copyOf(captor.getValue());
  }

  /**
   * 一筆還欠貨的需求。
   *
   * <p>不再造 {@code Order}——配貨看不到訂單，也不改它的狀態。「這張單被配到了」現在的觀察點
   * 是 {@code OrderAllocationCompleted} 事件，見 {@link #allocationCompletedFor}。
   */
  private Demand backorderedDemand(int quantity) {
    return DemandFixtures.demand(IdGenerator.nextId(), SKU, quantity);
  }

  /** 這筆需求配到了——由事件斷言，那是配貨對外唯一的陳述。 */
  private OrderAllocationCompleted allocationCompletedFor(Demand demand) {
    return new OrderAllocationCompleted(demand.orderId(), fixedNow);
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
        event.getOwnerId(), event.getNodeId(), OrderFixtures.LOCATION_ID, event.getSku(),
        event.getInDate(), event.getExpiryDate(), event.getQuantity());
  }

  private InboundCommand<ReplenishStockCommand> inbound(StockReplenishedIntegrationEvent event) {
    return new InboundCommand<>(command(event), message(event.getEventId()));
  }
}
