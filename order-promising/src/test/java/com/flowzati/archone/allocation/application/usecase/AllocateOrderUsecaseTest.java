package com.flowzati.archone.allocation.application.usecase;

import com.flowzati.archone.allocation.application.command.AllocateOrderCommand;
import com.flowzati.archone.allocation.application.coordinator.OrderAllocationCoordinator;
import com.flowzati.archone.allocation.domain.model.MoveState;
import com.flowzati.archone.allocation.domain.model.StockMove;
import com.flowzati.archone.allocation.domain.model.StockPicking;
import com.flowzati.archone.allocation.domain.model.StockPool;
import com.flowzati.archone.allocation.domain.model.StockFixtures;
import com.flowzati.archone.allocation.domain.repository.StockMoveRepository;
import com.flowzati.archone.allocation.domain.repository.StockPickingRepository;
import com.flowzati.archone.allocation.domain.service.AllocationOutcome;
import com.flowzati.archone.allocation.domain.repository.StockPoolRepository;
import com.flowzati.archone.catalog.domain.model.PickingDirection;
import com.flowzati.archone.catalog.domain.model.PickingType;
import com.flowzati.archone.catalog.domain.repository.PickingTypeRepository;
import com.flowzati.archone.catalog.domain.repository.StockLocationRepository;
import com.flowzati.archone.common.inbox.InboxRepo;
import com.flowzati.archone.common.time.BusinessCalendar;
import com.flowzati.archone.common.inbox.InboundCommand;
import com.flowzati.archone.common.inbox.MessageMetadata;
import com.flowzati.archone.allocation.domain.model.Demand;
import com.flowzati.archone.allocation.domain.repository.DemandRepository;
import com.flowzati.archone.testsupport.DemandFixtures;
import com.flowzati.archone.testsupport.MovementFixtures;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import static org.assertj.core.api.Assertions.assertThat;
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
  private DemandRepository demandRepository;
  private StockPoolRepository stockPoolRepository;
  private StockMoveRepository stockMoveRepository;
  private StockPickingRepository stockPickingRepository;
  private PickingTypeRepository pickingTypeRepository;
  private StockLocationRepository stockLocationRepository;
  private OrderAllocationCoordinator allocationCoordinator;

  @BeforeEach
  void setUp() {
    inboxRepo = mock(InboxRepo.class);
    demandRepository = mock(DemandRepository.class);
    stockPoolRepository = mock(StockPoolRepository.class);
    stockMoveRepository = mock(StockMoveRepository.class);
    stockPickingRepository = mock(StockPickingRepository.class);
    pickingTypeRepository = mock(PickingTypeRepository.class);
    stockLocationRepository = mock(StockLocationRepository.class);
    allocationCoordinator = mock(OrderAllocationCoordinator.class);

    usecase = new AllocateOrderUsecase(
        inboxRepo,
        demandRepository,
        stockPoolRepository,
        stockMoveRepository,
        stockPickingRepository,
        pickingTypeRepository,
        stockLocationRepository,
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
    verifyNoInteractions(demandRepository, stockPoolRepository, allocationCoordinator);
    verifyNoInteractions(stockMoveRepository, stockPickingRepository);
  }

  @Test
  @DisplayName("這張單已經沒有待配需求時，不應建立任何搬運")
  void shouldDoNothingWhenNothingIsOutstanding() {
    UUID messageId = UUID.randomUUID();
    UUID orderId = com.flowzati.archone.common.IdGenerator.nextId();
    given(inboxRepo.claimIfNew(message(messageId))).willReturn(true);
    // 已經建了搬運的行不會出現在 demand_lines 裡，取消的整張單也不會——兩種情形都讓 view 回空。
    //
    // **判準是 view，不是訂單狀態。** ordering 的配貨狀態由事件推進，落後於 allocation 自己
    // 的決策；拿它當閘門會讓同一筆需求被建第二次搬運。
    given(demandRepository.findByOrderId(orderId)).willReturn(Optional.empty());

    usecase.handle(inbound(new AllocateOrderCommand(orderId), messageId));

    then(demandRepository).should().findByOrderId(orderId);
    verifyNoInteractions(stockPoolRepository, allocationCoordinator);
    verifyNoInteractions(stockMoveRepository, stockPickingRepository);
  }

  @Test
  @DisplayName("查無待配需求時應為合法 no-op，不是錯誤")
  void shouldDoNothingWhenTheOrderHasNoOutstandingDemand() {
    UUID messageId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    given(inboxRepo.claimIfNew(message(messageId))).willReturn(true);
    given(demandRepository.findByOrderId(orderId)).willReturn(Optional.empty());

    // **不拋錯**，這是刻意的行為改變。改動前查的是訂單，查無代表資料不一致，該大聲失敗。
    // 現在查的是待配需求，而查無有兩種正常成因：這則命令重送（第一次已經建過搬運了），或
    // 訂單已被取消。把正常結果當成錯誤，會讓每一次重送都製造一筆 DLT 訊息。
    usecase.handle(inbound(new AllocateOrderCommand(orderId), messageId));

    verifyNoInteractions(stockPoolRepository, allocationCoordinator);
  }

  @Test
  @DisplayName("一件貨都沒有時仍要建搬運，狀態是還在等貨")
  void shouldRecordAMovementEvenWhenThereIsNothingToAllocate() {
    UUID messageId = UUID.randomUUID();
    Demand order = pendingDemand("SKU-1", 5);
    given(inboxRepo.claimIfNew(message(messageId))).willReturn(true);
    given(demandRepository.findByOrderId(order.orderId())).willReturn(Optional.of(order));
    givenAnOutboundOperationType();
    givenAllocatableBatches();
    given(allocationCoordinator.allocateOrder(order, Map.of("SKU-1", List.of()), fixedNow))
        .willReturn(AllocationOutcome.NO_ALLOCATABLE_STOCK);

    usecase.handle(inbound(new AllocateOrderCommand(order.orderId()), messageId));

    // 「還在等貨」因此是一列真實資料，不是查詢的副產物——一張永遠配不到的單留得下痕跡，
    // 而 createdAt 說得出它躺了多久。改動前這種單在系統裡什麼都沒有，只能靠訂單與預留的
    // 差集看出來。
    StockMove move = savedMoves().getFirst();
    assertThat(move.getState()).isEqualTo(MoveState.CONFIRMED);
    assertThat(move.getCreatedAt()).isEqualTo(fixedNow);
    assertThat(move.getAssignedAt()).isNull();
    assertThat(move.getSkuCode()).isEqualTo("SKU-1");
    assertThat(move.getDemandQuantity()).isEqualTo(5);
    assertThat(move.getOrderLineId()).isEqualTo(order.lines().getFirst().orderLineId());
  }

  @Test
  @DisplayName("搬運的起訖取自作業類型的預設值：庫存位置 → 客戶")
  void shouldTakeBothEndsFromTheOperationType() {
    UUID messageId = UUID.randomUUID();
    Demand order = pendingDemand("SKU-1", 5);
    given(inboxRepo.claimIfNew(message(messageId))).willReturn(true);
    given(demandRepository.findByOrderId(order.orderId())).willReturn(Optional.of(order));
    givenAnOutboundOperationType();
    givenAllocatableBatches();

    usecase.handle(inbound(new AllocateOrderCommand(order.orderId()), messageId));

    // 終點是虛擬的客戶位置——出庫的目的地本來就在公司之外，這正是搬運不能沿用庫存那條
    // 「只能指向內部位置」約束的理由。
    ArgumentCaptor<StockPicking> pickingCaptor = ArgumentCaptor.forClass(StockPicking.class);
    then(stockPickingRepository).should().save(pickingCaptor.capture(), org.mockito.ArgumentMatchers.eq(order.orderId()));
    assertThat(pickingCaptor.getValue().fromLocationId()).isEqualTo(DemandFixtures.LOCATION_ID);
    assertThat(pickingCaptor.getValue().toLocationId())
        .isEqualTo(MovementFixtures.CUSTOMERS_LOCATION_ID);
    assertThat(pickingCaptor.getValue().pickingTypeId())
        .isEqualTo(MovementFixtures.OUTBOUND_TYPE_ID);

    StockMove move = savedMoves().getFirst();
    assertThat(move.getPickingId()).isEqualTo(pickingCaptor.getValue().id());
    assertThat(move.getFromLocationId()).isEqualTo(DemandFixtures.LOCATION_ID);
    assertThat(move.getToLocationId()).isEqualTo(MovementFixtures.CUSTOMERS_LOCATION_ID);
  }

  @Test
  @DisplayName("搬運要在配貨之前建好")
  void shouldRecordTheMovementBeforeAttemptingToAllocate() {
    UUID messageId = UUID.randomUUID();
    Demand order = pendingDemand("SKU-1", 5);
    StockPool batch = stockPool("SKU-1", 10);
    given(inboxRepo.claimIfNew(message(messageId))).willReturn(true);
    given(demandRepository.findByOrderId(order.orderId())).willReturn(Optional.of(order));
    givenAnOutboundOperationType();
    givenAllocatableBatches(batch);
    given(allocationCoordinator.allocateOrder(order, Map.of("SKU-1", List.of(batch)), fixedNow))
        .willReturn(AllocationOutcome.ALLOCATED);

    usecase.handle(inbound(new AllocateOrderCommand(order.orderId()), messageId));

    // 順序不是偏好問題：配貨是「把既有的搬運轉成已鎖定」，找不到搬運它就會拋錯。反過來
    // 「配到才建」則讓待配需求沒有落腳處，而那正是這個改動要消滅的東西。
    InOrder inOrder =
        org.mockito.Mockito.inOrder(stockPickingRepository, stockMoveRepository, allocationCoordinator);
    inOrder.verify(stockPickingRepository).save(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(order.orderId()));
    inOrder.verify(stockMoveRepository).saveAll(org.mockito.ArgumentMatchers.any());
    inOrder.verify(allocationCoordinator).allocateOrder(order, Map.of("SKU-1", List.of(batch)), fixedNow);
  }

  @Test
  @DisplayName("倉沒有出庫作業類型時應拋錯，不得靜默收下")
  void shouldFailWhenTheWarehouseHasNoOutboundOperationType() {
    UUID messageId = UUID.randomUUID();
    Demand order = pendingDemand("SKU-1", 5);
    given(inboxRepo.claimIfNew(message(messageId))).willReturn(true);
    given(demandRepository.findByOrderId(order.orderId())).willReturn(Optional.of(order));
    given(stockLocationRepository.findById(DemandFixtures.LOCATION_ID))
        .willReturn(Optional.of(MovementFixtures.internalLocation()));
    given(pickingTypeRepository.find(StockFixtures.NODE_ID, PickingDirection.OUTBOUND))
        .willReturn(Optional.empty());

    // 「收下卻不記」會讓這張單的需求消失得無聲無息：它不會出現在任何佇列裡，因為佇列只
    // 回答「還在等貨的搬運」。大聲失敗才看得見設定漏了。
    assertThatThrownBy(() -> usecase.handle(inbound(new AllocateOrderCommand(order.orderId()), messageId)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no outbound operation type");

    verifyNoInteractions(stockMoveRepository, stockPickingRepository, allocationCoordinator);
  }

  @Test
  @DisplayName("一批可售的都沒有時應掛帳，不得丟例外")
  void shouldBackorderRatherThanFailWhenThereIsNoAllocatableStock() {
    UUID messageId = UUID.randomUUID();
    Demand order = pendingDemand("SKU-1", 5);
    given(inboxRepo.claimIfNew(message(messageId))).willReturn(true);
    given(demandRepository.findByOrderId(order.orderId())).willReturn(Optional.of(order));
    givenAnOutboundOperationType();
    givenAllocatableBatches();
    given(allocationCoordinator.allocateOrder(order, Map.of("SKU-1", List.of()), fixedNow))
        .willReturn(AllocationOutcome.NO_ALLOCATABLE_STOCK);

    usecase.handle(inbound(new AllocateOrderCommand(order.orderId()), messageId));

    // 缺貨是正常結果，不是訊息處理失敗。丟例外的話每一次缺貨都會走進重試與 DLT。
    then(allocationCoordinator).should().backorderOrder(order, fixedNow);
  }

  @Test
  @DisplayName("應以訂單的貨主、位置與今天去查可售批")
  void shouldQueryAllocatableBatchesByOwnerLocationAndToday() {
    UUID messageId = UUID.randomUUID();
    Demand order = pendingDemand("SKU-1", 5);
    StockPool batch = stockPool("SKU-1", 10);
    given(inboxRepo.claimIfNew(message(messageId))).willReturn(true);
    given(demandRepository.findByOrderId(order.orderId())).willReturn(Optional.of(order));
    givenAnOutboundOperationType();
    givenAllocatableBatches(batch);
    given(allocationCoordinator.allocateOrder(order, Map.of("SKU-1", List.of(batch)), fixedNow))
        .willReturn(AllocationOutcome.ALLOCATED);

    usecase.handle(inbound(new AllocateOrderCommand(order.orderId()), messageId));

    // 過期篩選與 FEFO 排序都在資料庫做——批數只會隨時間成長，把不可售的載進記憶體只為了
    // 丟掉是錯的方向。
    then(stockPoolRepository).should().findAllocatableBatchesBySku(
        DemandFixtures.OWNER_ID, DemandFixtures.LOCATION_ID, Set.of("SKU-1"),
        TODAY_IN_TAIPEI);
  }

  @Test
  @DisplayName("當庫存充足時，應成功分配訂單")
  void shouldAllocateSuccessfullyWhenStockIsEnough() {
    UUID messageId = UUID.randomUUID();
    Demand order = pendingDemand("SKU-1", 5);
    StockPool batch = stockPool("SKU-1", 10);
    given(inboxRepo.claimIfNew(message(messageId))).willReturn(true);
    given(demandRepository.findByOrderId(order.orderId())).willReturn(Optional.of(order));
    givenAnOutboundOperationType();
    givenAllocatableBatches(batch);
    given(allocationCoordinator.allocateOrder(order, Map.of("SKU-1", List.of(batch)), fixedNow))
        .willReturn(AllocationOutcome.ALLOCATED);

    usecase.handle(inbound(new AllocateOrderCommand(order.orderId()), messageId));

    then(allocationCoordinator).should().allocateOrder(order, Map.of("SKU-1", List.of(batch)), fixedNow);
    then(allocationCoordinator).should(org.mockito.Mockito.never())
        .backorderOrder(order, fixedNow);
  }

  @Test
  @DisplayName("當庫存不足時，應委派 Coordinator 標記欠單")
  void shouldBackorderWhenStockIsInsufficient() {
    UUID messageId = UUID.randomUUID();
    Demand order = pendingDemand("SKU-1", 5);
    StockPool batch = stockPool("SKU-1", 2);
    given(inboxRepo.claimIfNew(message(messageId))).willReturn(true);
    given(demandRepository.findByOrderId(order.orderId())).willReturn(Optional.of(order));
    givenAnOutboundOperationType();
    givenAllocatableBatches(batch);
    given(allocationCoordinator.allocateOrder(order, Map.of("SKU-1", List.of(batch)), fixedNow))
        .willReturn(AllocationOutcome.INSUFFICIENT_ATP);

    usecase.handle(inbound(new AllocateOrderCommand(order.orderId()), messageId));

    then(allocationCoordinator).should().backorderOrder(order, fixedNow);
  }

  /** 位置反查到倉，倉上有一個出庫作業類型。建搬運的前置條件。 */
  private void givenAnOutboundOperationType() {
    PickingType type = MovementFixtures.outboundType();
    given(stockLocationRepository.findById(DemandFixtures.LOCATION_ID))
        .willReturn(Optional.of(MovementFixtures.internalLocation()));
    given(pickingTypeRepository.find(StockFixtures.NODE_ID, PickingDirection.OUTBOUND))
        .willReturn(Optional.of(type));
  }

  @SuppressWarnings("unchecked")
  private List<StockMove> savedMoves() {
    ArgumentCaptor<Collection<StockMove>> captor = ArgumentCaptor.forClass(Collection.class);
    then(stockMoveRepository).should().saveAll(captor.capture());
    return List.copyOf(captor.getValue());
  }

  private void givenAllocatableBatches(StockPool... batches) {
    given(stockPoolRepository.findAllocatableBatchesBySku(
        DemandFixtures.OWNER_ID, DemandFixtures.LOCATION_ID, Set.of("SKU-1"),
        TODAY_IN_TAIPEI)).willReturn(Map.of("SKU-1", List.of(batches)));
  }

  private Demand pendingDemand(String sku, int quantity) {
    return DemandFixtures.demand(
        com.flowzati.archone.common.IdGenerator.nextId(), sku, quantity);
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
