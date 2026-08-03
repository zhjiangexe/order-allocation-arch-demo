package com.flowzati.archone.stock.application.usecase;

import com.flowzati.archone.common.time.AppClock;
import com.flowzati.archone.stock.application.command.ReplenishStockCommand;
import com.flowzati.archone.stock.application.movement.MovementAssigner;
import com.flowzati.archone.stock.application.movement.MovementCompleter;
import com.flowzati.archone.stock.application.movement.MovementRecorder;
import com.flowzati.archone.stock.domain.event.BackorderWakeContinuationRequired;
import com.flowzati.archone.stock.application.event.StockReplenishedIntegrationEvent;
import com.flowzati.archone.stock.domain.model.StockMove;
import com.flowzati.archone.stock.domain.model.StockPool;
import com.flowzati.archone.stock.domain.model.StockFixtures;
import com.flowzati.archone.stock.domain.repository.StockMoveRepository;
import com.flowzati.archone.stock.domain.repository.StockPoolRepository;
import com.flowzati.archone.common.inbox.InboxRepo;
import com.flowzati.archone.common.inbox.InboundCommand;
import com.flowzati.archone.common.inbox.MessageMetadata;
import com.flowzati.archone.stock.domain.event.OrderAllocationCompleted;
import com.flowzati.archone.stock.domain.model.Demand;
import com.flowzati.archone.common.IdGenerator;
import com.flowzati.archone.testsupport.DemandFixtures;
import com.flowzati.archone.testsupport.MovementFixtures;
import com.flowzati.archone.testsupport.OrderFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
  private MovementRecorder movementRecorder;
  private MovementCompleter movementCompleter;
  private MovementAssigner movementAssigner;
  private StockMoveRepository stockMoveRepository;
  private InboxRepo inboxRepo;
  private ApplicationEventPublisher eventPublisher;
  private ReplenishmentUsecase replenishmentUsecase;

  @BeforeEach
  void setUp() {
    stockPoolRepository = mock(StockPoolRepository.class);
    movementRecorder = mock(MovementRecorder.class);
    movementCompleter = mock(MovementCompleter.class);
    movementAssigner = mock(MovementAssigner.class);
    stockMoveRepository = mock(StockMoveRepository.class);
    eventPublisher = mock(ApplicationEventPublisher.class);
    inboxRepo = mock(InboxRepo.class);

    replenishmentUsecase = new ReplenishmentUsecase(
        Clock.fixed(fixedNow, ZoneId.of("UTC")),
        new AppClock(Clock.fixed(fixedNow, ZoneId.of("UTC")), "Asia/Taipei"),
        inboxRepo,
        stockMoveRepository,
        stockPoolRepository,
        movementRecorder,
        movementCompleter,
        movementAssigner,
        eventPublisher,
        WAKE_LIMIT
    );
  }



  @Test
  @DisplayName("當收到新的補充事件時，應先收貨再把佇列交給鎖定那一步")
  void shouldReceiveThenHandTheQueueToAssignment() {
    UUID eventId = UUID.randomUUID();
    StockPool batch = StockFixtures.unexpiredBatch(SKU, 10, 0);
    when(inboxRepo.claimIfNew(message(eventId))).thenReturn(true);
    List<StockMove> incoming = givenAnInboundMovement();
    givenAllocatableBatches(List.of(batch));
    givenQueue(1, List.of(backorderedDemand(5)));

    replenishmentUsecase.handle(inbound(event(eventId, 10)));

    verify(inboxRepo).claimIfNew(message(eventId));

    // **這支 usecase 不再自己加庫存**——它建一段入庫搬運並完成它，數量由完成那一步的明細
    // 去加。曾經這裡是 stockPool.replenish(qty)，那是最後一條繞過搬運的路。
    verify(movementRecorder).recordInbound(
        OrderFixtures.OWNER_ID, OrderFixtures.LOCATION_ID, SKU, 10, fixedNow);
    verify(movementCompleter).complete(
        incoming,
        new MovementCompleter.BatchIdentity(IN_DATE, EXPIRY_DATE),
        fixedNow);

    // **順序是這支 usecase 的責任**：貨要先進來，佇列才看得到那些量。反過來的話，這一輪
    // 讀到的可承諾量還是收貨前的，整輪會什麼都配不到。
    InOrder inOrder = org.mockito.Mockito.inOrder(movementCompleter, movementAssigner);
    inOrder.verify(movementCompleter).complete(any(), any(), eq(fixedNow));
    inOrder.verify(movementAssigner).assignAll(any(), eq(fixedNow));
  }

  @Test
  @DisplayName("當沒有待處理訂單時，貨照收但不進行分配")
  void shouldOnlyReceiveWhenNoPendingOrders() {
    UUID eventId = UUID.randomUUID();
    StockPool batch = StockFixtures.unexpiredBatch(SKU, 10, 0);
    when(inboxRepo.claimIfNew(message(eventId))).thenReturn(true);
    givenAnInboundMovement();
    givenAllocatableBatches(List.of(batch));
    givenAnEmptyQueue();

    replenishmentUsecase.handle(inbound(event(eventId, 10)));

    verify(movementCompleter).complete(any(), any(), eq(fixedNow));
    verifyNoInteractions(movementAssigner);
    verify(eventPublisher, never()).publishEvent(any());
  }

  @Test
  @DisplayName("喚醒張數達到上限時應發一則續做的領域事件，帶著爭用群組的三個維度")
  void shouldRequestContinuationWhenTheWakeLimitIsReached() {
    UUID eventId = UUID.randomUUID();
    StockPool batch = StockFixtures.unexpiredBatch(SKU, 0, 0);
    when(inboxRepo.claimIfNew(message(eventId))).thenReturn(true);
    givenAnInboundMovement();
    givenAllocatableBatches(List.of(batch));
    givenQueue(WAKE_LIMIT,
        IntStream.range(0, WAKE_LIMIT).mapToObj(i -> backorderedDemand(1)).toList());

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
    givenAnInboundMovement();
    givenAllocatableBatches(List.of(batch));
    // 讀滿了上限，卻一張都沒配到——head-of-line blocker 卡在隊首就是這個形狀。再送一次結果
    // 完全相同，所以判準必須是「配到幾張」而不是「讀到幾張」，否則這裡會無限續做。
    givenQueue(WAKE_LIMIT, List.of());

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
    givenQueue(1, List.of(queued));

    replenishmentUsecase.handleWake(new InboundCommand<>(
        new com.flowzati.archone.stock.application.command.WakeBackordersCommand(
            OrderFixtures.OWNER_ID, OrderFixtures.NODE_ID, OrderFixtures.LOCATION_ID, SKU),
        message(eventId)));

    assertThat(batch.getOnHandQuantity()).isEqualTo(10);
    // 佇列確實被餵完了——續做只是接著配，不是重新收一次貨。
    verify(movementAssigner).assignAll(any(), eq(fixedNow));
    verifyNoInteractions(movementRecorder, movementCompleter);
  }

  @Test
  @DisplayName("當事件已被處理過時，不應重複執行補充邏輯")
  void shouldDoNothingWhenEventIsAlreadyProcessed() {
    UUID eventId = UUID.randomUUID();
    when(inboxRepo.claimIfNew(message(eventId))).thenReturn(false);

    replenishmentUsecase.handle(inbound(event(eventId, 10)));

    verify(inboxRepo).claimIfNew(message(eventId));
    verifyNoInteractions(stockPoolRepository, stockMoveRepository, movementAssigner, eventPublisher);
  }

  @Test
  @DisplayName("補進來的批已過期時不應喚醒任何訂單")
  void shouldNotWakeAnyOrderWhenTheReplenishedBatchIsAlreadyExpired() {
    UUID eventId = UUID.randomUUID();
    when(inboxRepo.claimIfNew(message(eventId))).thenReturn(true);
    givenAnInboundMovement();
    // 可售批查詢在資料庫就把過期的濾掉了，因此這裡回空。
    givenAllocatableBatches(List.of());

    replenishmentUsecase.handle(inbound(event(eventId, 10)));

    // **貨照收**——過期的貨倉庫裡真的有，記下來是對的；配不配得到是另一回事。
    verify(movementCompleter).complete(any(), any(), eq(fixedNow));
    // 守門查詢就擋下來了，佇列連查都不必查。
    verifyNoInteractions(stockMoveRepository, movementAssigner, eventPublisher);
  }

  /**
   * 到貨會建出一段入庫搬運。
   *
   * <p>「貨落在哪一批」不在這裡驗——那是完成那一步的責任，由 {@code MovementCompleterTest}
   * 守著。這支測試只確認 usecase 有把事情交出去，以及交出去的順序。
   */
  private List<StockMove> givenAnInboundMovement() {
    List<StockMove> incoming = List.of(MovementFixtures.waitingMove(
        IdGenerator.nextId(), SKU, null, 10, fixedNow));
    when(movementRecorder.recordInbound(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(), any()))
        .thenReturn(incoming);
    return incoming;
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
   * 佇列裡有這些還在等貨的搬運，而鎖定那一步會配到 {@code allocated} 這幾張。
   *
   * <p><b>兩者分開 stub 是刻意的。</b>「讀到幾張」與「配到幾張」在這支 usecase 裡是不同的量，
   * 而續做的終止條件依賴後者——head-of-line blocker 卡住時每一輪都讀滿上限卻配不到任何一張，
   * 以讀取數當判準就會無限續做。合成一個 stub，這個區別在測試裡就消失了。
   */
  private void givenQueue(int waitingCount, List<Demand> allocated) {
    List<StockMove> waiting = IntStream.range(0, waitingCount)
        .mapToObj(i -> MovementFixtures.waitingMove(
            IdGenerator.nextId(), SKU, IdGenerator.nextId(), 1, fixedNow.minusSeconds(2)))
        .toList();
    when(stockMoveRepository.findWaitingInFifoOrder(
        OrderFixtures.OWNER_ID, OrderFixtures.LOCATION_ID, SKU, WAKE_LIMIT)).thenReturn(waiting);
    when(movementAssigner.assignAll(waiting, fixedNow)).thenReturn(allocated);
  }

  private void givenAnEmptyQueue() {
    when(stockMoveRepository.findWaitingInFifoOrder(
        OrderFixtures.OWNER_ID, OrderFixtures.LOCATION_ID, SKU, WAKE_LIMIT))
        .thenReturn(List.of());
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
