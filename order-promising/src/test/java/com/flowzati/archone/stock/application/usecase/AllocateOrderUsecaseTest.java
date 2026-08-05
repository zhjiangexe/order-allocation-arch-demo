package com.flowzati.archone.stock.application.usecase;

import com.flowzati.archone.stock.application.command.AllocateOrderCommand;
import com.flowzati.archone.stock.application.movement.MovementAssigner;
import com.flowzati.archone.stock.application.movement.StockOperationRecorder;
import com.flowzati.archone.stock.domain.event.OrderAllocationCompleted;
import com.flowzati.archone.stock.domain.event.OrderBackorderRecorded;
import com.flowzati.archone.stock.domain.model.StockMove;
import com.flowzati.archone.stock.domain.service.AllocationOutcome;
import com.flowzati.archone.common.IdGenerator;
import com.flowzati.archone.common.inbox.InboxRepo;
import com.flowzati.archone.common.inbox.InboundCommand;
import com.flowzati.archone.common.inbox.MessageMetadata;
import com.flowzati.archone.stock.domain.model.Demand;
import com.flowzati.archone.stock.domain.repository.DemandRepository;
import com.flowzati.archone.testsupport.DemandFixtures;
import com.flowzati.archone.testsupport.MovementFixtures;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * 收單後的處置。
 *
 * <p><b>這支測試刻意很薄。</b>建搬運的內容歸 {@code StockOperationRecorderTest}、鎖定的內容歸
 * {@code MovementAssignerTest}；這裡只驗 usecase 真正的責任：冪等、讀輸入、決定後續動作，
 * 以及那幾件事的先後。
 */
class AllocateOrderUsecaseTest {

  /** 台北 2026-07-22 早上 7 點——UTC 此刻還停在 07-21，剛好落在會出錯的那八小時內。 */
  private final Instant fixedNow = Instant.parse("2026-07-21T23:00:00Z");

  private AllocateOrderUsecase usecase;
  private InboxRepo inboxRepo;
  private DemandRepository demandRepository;
  private StockOperationRecorder stockOperationRecorder;
  private MovementAssigner movementAssigner;
  private ApplicationEventPublisher eventPublisher;

  @BeforeEach
  void setUp() {
    inboxRepo = mock(InboxRepo.class);
    demandRepository = mock(DemandRepository.class);
    stockOperationRecorder = mock(StockOperationRecorder.class);
    movementAssigner = mock(MovementAssigner.class);
    eventPublisher = mock(ApplicationEventPublisher.class);

    usecase = new AllocateOrderUsecase(
        inboxRepo,
        demandRepository,
        stockOperationRecorder,
        movementAssigner,
        eventPublisher,
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
    verifyNoInteractions(demandRepository, stockOperationRecorder, movementAssigner, eventPublisher);
  }

  @Test
  @DisplayName("這張單已經沒有待配需求時，不應建立任何搬運")
  void shouldDoNothingWhenNothingIsOutstanding() {
    UUID messageId = UUID.randomUUID();
    UUID orderId = IdGenerator.nextId();
    given(inboxRepo.claimIfNew(message(messageId))).willReturn(true);
    // 已經建了搬運的行不會出現在 demand_lines 裡，取消的整張單也不會——兩種情形都讓 view 回空。
    //
    // **判準是 view，不是訂單狀態。** ordering 的配貨狀態由事件推進，落後於 allocation 自己
    // 的決策；拿它當閘門會讓同一筆需求被建第二次搬運。
    given(demandRepository.findByOrderId(orderId)).willReturn(Optional.empty());

    usecase.handle(inbound(new AllocateOrderCommand(orderId), messageId));

    then(demandRepository).should().findByOrderId(orderId);
    verifyNoInteractions(stockOperationRecorder, movementAssigner, eventPublisher);
  }

  @Test
  @DisplayName("搬運要在配貨之前建好，而且建好的那些就是拿去配的那些")
  void shouldRecordTheMovementBeforeAssigningAndPassThemAlong() {
    UUID messageId = UUID.randomUUID();
    Demand demand = pendingDemand();
    List<StockMove> moves = movesFor(demand);
    givenTheOrderIsOutstanding(messageId, demand);
    given(stockOperationRecorder.recordOutbound(demand, fixedNow)).willReturn(moves);
    given(movementAssigner.assign(demand, moves, fixedNow))
        .willReturn(AllocationOutcome.ALLOCATED);

    usecase.handle(inbound(new AllocateOrderCommand(demand.orderId()), messageId));

    // 順序不是偏好問題：鎖定是「把既有的搬運轉成已鎖定」。反過來「配到才建」則讓待配需求
    // 沒有落腳處，而那正是上一個 change 消滅的東西。
    //
    // **傳遞也不是細節**：建好的搬運直接交出去，鎖定才不必回頭再讀一次。
    InOrder inOrder = org.mockito.Mockito.inOrder(stockOperationRecorder, movementAssigner);
    inOrder.verify(stockOperationRecorder).recordOutbound(demand, fixedNow);
    inOrder.verify(movementAssigner).assign(demand, moves, fixedNow);
  }

  @Test
  @DisplayName("配到貨時只發一個完成事實")
  void shouldPublishExactlyOneCompletionWhenAllocated() {
    UUID messageId = UUID.randomUUID();
    Demand demand = pendingDemand();
    givenTheOrderIsOutstanding(messageId, demand);
    given(movementAssigner.assign(any(), any(), any()))
        .willReturn(AllocationOutcome.ALLOCATED);

    usecase.handle(inbound(new AllocateOrderCommand(demand.orderId()), messageId));

    then(eventPublisher).should(times(1))
        .publishEvent(new OrderAllocationCompleted(demand.orderId(), fixedNow));
    then(eventPublisher).should(never()).publishEvent(any(OrderBackorderRecorded.class));
    verifyNoMoreInteractions(eventPublisher);
  }

  @Test
  @DisplayName("一批可售的都沒有時應發缺貨的事實，不得丟例外")
  void shouldRecordABackorderRatherThanFailWhenThereIsNoAllocatableStock() {
    UUID messageId = UUID.randomUUID();
    Demand demand = pendingDemand();
    givenTheOrderIsOutstanding(messageId, demand);
    given(movementAssigner.assign(any(), any(), any()))
        .willReturn(AllocationOutcome.NO_ALLOCATABLE_STOCK);

    usecase.handle(inbound(new AllocateOrderCommand(demand.orderId()), messageId));

    // 缺貨是正常結果，不是訊息處理失敗。丟例外的話每一次缺貨都會走進重試與 DLT。
    //
    // 缺貨事實留在這支 usecase，而不是交給鎖定那一步：補貨路徑配不到時什麼都不發，兩條
    // 路徑的處置不同。
    then(eventPublisher).should(times(1))
        .publishEvent(new OrderBackorderRecorded(demand.orderId(), fixedNow));
    verifyNoMoreInteractions(eventPublisher);
  }

  @Test
  @DisplayName("庫存不足時同樣發缺貨的事實——兩種配不到在這一層沒有差別")
  void shouldRecordABackorderWhenStockIsInsufficient() {
    UUID messageId = UUID.randomUUID();
    Demand demand = pendingDemand();
    givenTheOrderIsOutstanding(messageId, demand);
    given(movementAssigner.assign(any(), any(), any()))
        .willReturn(AllocationOutcome.INSUFFICIENT_ATP);

    usecase.handle(inbound(new AllocateOrderCommand(demand.orderId()), messageId));

    then(eventPublisher).should(times(1))
        .publishEvent(new OrderBackorderRecorded(demand.orderId(), fixedNow));
    verifyNoMoreInteractions(eventPublisher);
  }

  private void givenTheOrderIsOutstanding(UUID messageId, Demand demand) {
    given(inboxRepo.claimIfNew(message(messageId))).willReturn(true);
    given(demandRepository.findByOrderId(demand.orderId())).willReturn(Optional.of(demand));
  }

  private Demand pendingDemand() {
    return DemandFixtures.demand(IdGenerator.nextId(), "SKU-1", 5);
  }

  private List<StockMove> movesFor(Demand demand) {
    UUID pickingId = IdGenerator.nextId();
    return demand.lines().stream()
        .map(line -> MovementFixtures.waitingMove(
            pickingId, line.skuCode(), line.orderLineId(), line.quantity(), fixedNow))
        .toList();
  }

  private MessageMetadata message(UUID eventId) {
    return new MessageMetadata(eventId, "OrderPlacedIntegrationEvent");
  }

  private InboundCommand<AllocateOrderCommand> inbound(AllocateOrderCommand command, UUID eventId) {
    return new InboundCommand<>(command, message(eventId));
  }
}
