package com.flowzati.archone.stock.application.usecase;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.flowzati.archone.common.IdGenerator;
import com.flowzati.archone.common.inbox.InboundCommand;
import com.flowzati.archone.common.inbox.InboxRepo;
import com.flowzati.archone.common.inbox.MessageMetadata;
import com.flowzati.archone.common.time.AppClock;
import com.flowzati.archone.stock.application.command.AllocateWaitingDemandCommand;
import com.flowzati.archone.stock.application.event.StockAvailabilityIncreasedIntegrationEvent;
import com.flowzati.archone.stock.application.movement.MovementAssigner;
import com.flowzati.archone.stock.domain.event.OrderAllocationCompleted;
import com.flowzati.archone.stock.domain.model.Demand;
import com.flowzati.archone.stock.domain.model.StockFixtures;
import com.flowzati.archone.stock.domain.model.StockMove;
import com.flowzati.archone.stock.domain.repository.StockMoveRepository;
import com.flowzati.archone.stock.domain.repository.StockPoolRepository;
import com.flowzati.archone.testsupport.DemandFixtures;
import com.flowzati.archone.testsupport.MovementFixtures;
import com.flowzati.archone.testsupport.OrderFixtures;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

@DisplayName("配置一輪等待需求")
class AllocateWaitingDemandUsecaseTest {

  private static final int ALLOCATION_LIMIT = 3;
  private static final String SKU = "SKU-1";
  private static final Instant NOW = Instant.parse("2026-08-03T01:00:00Z");
  private static final LocalDate TODAY = LocalDate.of(2026, 8, 3);

  private InboxRepo inboxRepo;
  private StockPoolRepository stockPoolRepository;
  private StockMoveRepository stockMoveRepository;
  private MovementAssigner movementAssigner;
  private ApplicationEventPublisher eventPublisher;
  private AllocateWaitingDemandUsecase usecase;

  @BeforeEach
  void setUp() {
    inboxRepo = mock(InboxRepo.class);
    stockPoolRepository = mock(StockPoolRepository.class);
    stockMoveRepository = mock(StockMoveRepository.class);
    movementAssigner = mock(MovementAssigner.class);
    eventPublisher = mock(ApplicationEventPublisher.class);
    usecase = new AllocateWaitingDemandUsecase(
        inboxRepo,
        stockPoolRepository,
        stockMoveRepository,
        movementAssigner,
        eventPublisher,
        new AppClock(Clock.fixed(NOW, ZoneId.of("UTC")), "Asia/Taipei"),
        ALLOCATION_LIMIT);
  }

  @Test
  @DisplayName("新 availability 訊息先 claim Inbox，再執行等待需求配貨")
  void shouldClaimThenAllocateWaitingDemand() {
    InboundCommand<AllocateWaitingDemandCommand> inbound = inbound(UUID.randomUUID());
    List<StockMove> waiting = givenWaitingMoves(1);
    Demand allocated = demand();
    when(inboxRepo.claimIfNew(inbound.message())).thenReturn(true);
    when(movementAssigner.assignWaitingBatch(waiting, NOW)).thenReturn(List.of(allocated));

    usecase.handle(inbound);

    verify(inboxRepo).claimIfNew(inbound.message());
    verify(eventPublisher).publishEvent(
        new OrderAllocationCompleted(allocated.orderId(), NOW));
  }

  @Test
  @DisplayName("重複 availability 訊息不執行任何配貨工作")
  void shouldReturnNoProgressForADuplicateMessage() {
    InboundCommand<AllocateWaitingDemandCommand> inbound = inbound(UUID.randomUUID());
    when(inboxRepo.claimIfNew(inbound.message())).thenReturn(false);

    usecase.handle(inbound);

    verifyNoInteractions(
        stockPoolRepository, stockMoveRepository, movementAssigner, eventPublisher);
  }

  @Test
  @DisplayName("沒有可配庫存時停在守門查詢")
  void shouldStopWhenThereIsNoAllocatableStock() {
    when(stockPoolRepository.findAllocatableBatchesInFefoOrder(
        OrderFixtures.OWNER_ID, OrderFixtures.LOCATION_ID, SKU, TODAY))
        .thenReturn(List.of());

    usecase.handle(command());

    verifyNoInteractions(inboxRepo);
    verifyNoInteractions(stockMoveRepository, movementAssigner, eventPublisher);
  }

  @Test
  @DisplayName("等待佇列為空時不執行配貨")
  void shouldStopWhenTheQueueIsEmpty() {
    givenAllocatableStock();
    when(stockMoveRepository.findWaitingInFifoOrder(
        OrderFixtures.OWNER_ID, OrderFixtures.LOCATION_ID, SKU, ALLOCATION_LIMIT))
        .thenReturn(List.of());

    usecase.handle(command());

    verifyNoInteractions(movementAssigner, eventPublisher);
  }

  @Test
  @DisplayName("讀滿但被隊首擋住時不發布完成事實")
  void shouldStopWithoutPublishingWhenAFullPageMakesNoProgress() {
    List<StockMove> waiting = givenWaitingMoves(ALLOCATION_LIMIT);
    when(movementAssigner.assignWaitingBatch(waiting, NOW)).thenReturn(List.of());

    usecase.handle(command());

    verify(eventPublisher, never()).publishEvent(any());
  }

  @Test
  @DisplayName("部分有進展時每張成功單只發布一個完成事實")
  void shouldPublishOneCompletionPerAllocatedOrderForAPartialRound() {
    List<StockMove> waiting = givenWaitingMoves(ALLOCATION_LIMIT);
    List<Demand> allocated = List.of(demand(), demand());
    when(movementAssigner.assignWaitingBatch(waiting, NOW)).thenReturn(allocated);

    usecase.handle(command());

    verify(eventPublisher, times(2)).publishEvent(any(OrderAllocationCompleted.class));
    allocated.forEach(demand -> verify(eventPublisher)
        .publishEvent(new OrderAllocationCompleted(demand.orderId(), NOW)));
  }

  private AllocateWaitingDemandCommand command() {
    return new AllocateWaitingDemandCommand(
        OrderFixtures.OWNER_ID,
        OrderFixtures.FACILITY_ID,
        OrderFixtures.LOCATION_ID,
        SKU);
  }

  private InboundCommand<AllocateWaitingDemandCommand> inbound(UUID eventId) {
    return new InboundCommand<>(command(), new MessageMetadata(
        eventId, StockAvailabilityIncreasedIntegrationEvent.class.getSimpleName()));
  }

  private void givenAllocatableStock() {
    when(stockPoolRepository.findAllocatableBatchesInFefoOrder(
        OrderFixtures.OWNER_ID, OrderFixtures.LOCATION_ID, SKU, TODAY))
        .thenReturn(List.of(StockFixtures.unexpiredBatch(SKU, 10, 0)));
  }

  private List<StockMove> givenWaitingMoves(int count) {
    givenAllocatableStock();
    List<StockMove> waiting = IntStream.range(0, count)
        .mapToObj(index -> MovementFixtures.waitingMove(
            IdGenerator.nextId(), SKU, IdGenerator.nextId(), 1, NOW.minusSeconds(1)))
        .toList();
    when(stockMoveRepository.findWaitingInFifoOrder(
        OrderFixtures.OWNER_ID, OrderFixtures.LOCATION_ID, SKU, ALLOCATION_LIMIT))
        .thenReturn(waiting);
    return waiting;
  }

  private Demand demand() {
    return DemandFixtures.demand(IdGenerator.nextId(), SKU, 1);
  }
}
