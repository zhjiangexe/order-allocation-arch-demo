package com.flowzati.archone.stock.application.movement;

import com.flowzati.archone.stock.domain.event.OrderAllocationCompleted;
import com.flowzati.archone.stock.domain.model.Demand;
import com.flowzati.archone.stock.domain.model.MoveState;
import com.flowzati.archone.stock.domain.model.StockFixtures;
import com.flowzati.archone.stock.domain.model.StockMove;
import com.flowzati.archone.stock.domain.model.StockMoveLine;
import com.flowzati.archone.stock.domain.model.StockPicking;
import com.flowzati.archone.stock.domain.model.StockPool;
import com.flowzati.archone.stock.domain.repository.StockMoveRepository;
import com.flowzati.archone.stock.domain.repository.StockPickingRepository;
import com.flowzati.archone.stock.domain.repository.StockPoolRepository;
import com.flowzati.archone.stock.domain.service.AllocationOutcome;
import com.flowzati.archone.stock.domain.service.AllocationService;
import com.flowzati.archone.common.IdGenerator;
import com.flowzati.archone.common.time.BusinessCalendar;
import com.flowzati.archone.testsupport.DemandFixtures;
import com.flowzati.archone.testsupport.MovementFixtures;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("鎖定搬運")
class MovementAssignerTest {

  private static final LocalDate NEAR_EXPIRY = LocalDate.of(2026, 8, 31);
  private static final LocalDate FAR_EXPIRY = LocalDate.of(2027, 1, 31);

  /** 台北 2026-07-23 早上 8 點——今天是 07-23，而 UTC 還停在 07-22。 */
  private final Instant now = Instant.parse("2026-07-23T00:00:00Z");
  private final LocalDate today = LocalDate.of(2026, 7, 23);
  private final Instant createdAt = now.minusSeconds(60);

  private StockPoolRepository stockPoolRepository;
  private StockMoveRepository stockMoveRepository;
  private StockPickingRepository stockPickingRepository;
  private ApplicationEventPublisher eventPublisher;
  private MovementAssigner assigner;

  @BeforeEach
  void setUp() {
    stockPoolRepository = mock(StockPoolRepository.class);
    stockMoveRepository = mock(StockMoveRepository.class);
    stockPickingRepository = mock(StockPickingRepository.class);
    eventPublisher = mock(ApplicationEventPublisher.class);
    assigner = new MovementAssigner(
        new AllocationService(),
        stockPoolRepository,
        stockMoveRepository,
        stockPickingRepository,
        new BusinessCalendar(Clock.fixed(now, ZoneId.of("UTC")), "Asia/Taipei"),
        eventPublisher
    );
  }

  @Test
  @DisplayName("配到貨時應把搬運轉為已鎖定、寫出明細，並統一儲存批次")
  void shouldAssignTheMovementAndWriteItsLinesWhenAllocationSucceeds() {
    Demand demand = pendingDemand("SKU-1", 5);
    StockPool batch = stockPool("SKU-1", 10);
    List<StockMove> moves = movesFor(demand);
    givenAllocatableBatches(demand, batch);

    AllocationOutcome outcome = assigner.assign(demand, moves, now);

    assertThat(outcome).isEqualTo(AllocationOutcome.ALLOCATED);
    assertThat(batch.getReservedQuantity()).isEqualTo(5);
    verify(stockPoolRepository).save(batch);

    // 搬運是**轉狀態**而不是新建：它在收單時就存在了。時刻分成兩個——建立的時刻說它等了
    // 多久，鎖定的時刻說貨什麼時候到手，兩者回答不同的問題。
    StockMove move = moves.getFirst();
    assertThat(move.getState()).isEqualTo(MoveState.ASSIGNED);
    assertThat(move.getAssignedAt()).isEqualTo(now);
    assertThat(move.getCreatedAt()).isEqualTo(createdAt);
    assertThat(savedMoves()).containsExactly(move);

    StockMoveLine line = savedLines().getFirst();
    assertThat(line.moveId()).isEqualTo(move.getId());
    assertThat(line.stockPoolId()).isEqualTo(batch.getId());
    assertThat(line.quantity()).isEqualTo(5);

    ArgumentCaptor<OrderAllocationCompleted> eventCaptor =
        ArgumentCaptor.forClass(OrderAllocationCompleted.class);
    verify(eventPublisher).publishEvent(eventCaptor.capture());
    // 事件只通知「這張單配好了」，不攜帶配到哪些批——那份資訊在 stock_move_lines 裡，
    // 且取消會改變它，事件抄一份只會多一個對不上的來源。
    assertThat(eventCaptor.getValue()).isEqualTo(
        new OrderAllocationCompleted(demand.orderId(), now));
  }

  @Test
  @DisplayName("搬運由呼叫端傳入，不得回頭再讀一次")
  void shouldUseTheMovementsItWasGivenInsteadOfReadingThemBack() {
    Demand demand = pendingDemand("SKU-1", 5);
    StockPool batch = stockPool("SKU-1", 10);
    givenAllocatableBatches(demand, batch);

    assigner.assign(demand, movesFor(demand), now);

    // 這條守的是這個 change 消掉的那次往返。收單剛建完、補貨剛從佇列讀出來——兩個呼叫端
    // 手上本來就有這些搬運，回頭用 order_line_id 再查一次是分層的副作用。
    verify(stockMoveRepository, never()).findByPickingIds(any());
    verifyNoInteractions(stockPickingRepository);
  }

  @Test
  @DisplayName("一條行吃到兩批時應產生兩條明細，數量加總等於該行的量")
  void shouldCreateOneMoveLinePerBatchTaken() {
    Demand demand = pendingDemand("SKU-1", 80);
    StockPool near = StockFixtures.batchExpiringOn("SKU-1", NEAR_EXPIRY, 60, 0);
    StockPool far = StockFixtures.batchExpiringOn("SKU-1", FAR_EXPIRY, 40, 0);
    List<StockMove> moves = movesFor(demand);
    givenAllocatableBatches(demand, near, far);

    assigner.assign(demand, moves, now);

    // 粒度是行 × 批。摺成一條行一列會丟掉「哪一批是為哪一條行鎖的」，而出貨時要的正是它。
    // 這條性質原本由 stock_reservations 守著，換到 stock_move_lines 上必須原樣成立。
    List<StockMoveLine> lines = savedLines();
    assertThat(lines).hasSize(2);
    assertThat(lines).allSatisfy(
        line -> assertThat(line.moveId()).isEqualTo(moves.getFirst().getId()));
    assertThat(lines).map(StockMoveLine::stockPoolId)
        .containsExactly(near.getId(), far.getId());
    assertThat(lines.stream().mapToInt(StockMoveLine::quantity).sum()).isEqualTo(80);

    // 一條行對兩條明細，但搬運只有一段——它不會因為跨批而被寫兩次。
    assertThat(savedMoves()).containsExactly(moves.getFirst());
  }

  @Test
  @DisplayName("批次寫入應依 (SKU, 效期, 入庫日, id) 排序，不得跟著輸入順序走")
  void shouldWriteBatchesInADeterministicOrderRegardlessOfInputOrder() {
    Demand demand = pendingDemand("SKU-1", 100);
    StockPool near = StockFixtures.batchExpiringOn("SKU-1", NEAR_EXPIRY, 60, 0);
    StockPool far = StockFixtures.batchExpiringOn("SKU-1", FAR_EXPIRY, 40, 0);
    // 刻意把遠效期放前面。FEFO 查詢碰巧已經排好，但那是查詢的實作細節；取消與補貨兩條路徑
    // 的集合來源完全不同，寫入順序必須自己保證，否則相反順序的兩個交易會互相等成死鎖。
    givenAllocatableBatches(demand, far, near);

    assigner.assign(demand, movesFor(demand), now);

    ArgumentCaptor<StockPool> captor = ArgumentCaptor.forClass(StockPool.class);
    verify(stockPoolRepository, org.mockito.Mockito.times(2)).save(captor.capture());
    assertThat(captor.getAllValues()).containsExactly(near, far);
  }

  @Test
  @DisplayName("配貨算出要動一條沒有搬運的行時應拋錯，不得靜默略過")
  void shouldRejectAPickForALineWithNoMovementSupplied() {
    Demand demand = pendingDemand("SKU-1", 5);
    givenAllocatableBatches(demand, stockPool("SKU-1", 10));

    // 呼叫端傳入的搬運與需求對不起來，代表兩者已經不同步。靜默略過會讓庫存被鎖住卻沒有
    // 任何東西記得那是為誰鎖的。
    assertThatThrownBy(() -> assigner.assign(demand, List.of(), now))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("No movement was supplied for order line");
  }

  @Test
  @DisplayName("單筆訂單分配失敗時不應儲存未變更的批次")
  void shouldNotSaveStockPoolWhenAllocationFails() {
    Demand demand = pendingDemand("SKU-1", 5);
    StockPool batch = stockPool("SKU-1", 2);
    givenAllocatableBatches(demand, batch);

    AllocationOutcome outcome = assigner.assign(demand, movesFor(demand), now);

    assertThat(outcome).isEqualTo(AllocationOutcome.INSUFFICIENT_ATP);
    assertThat(batch.getReservedQuantity()).isZero();
    verify(stockPoolRepository, never()).save(batch);
    verify(stockMoveRepository, never()).saveAll(any());
    verifyNoInteractions(eventPublisher);
  }

  @Test
  @DisplayName("沒有任何可售批時不應寫入任何東西，也不應為了問「為什麼」再查一次庫存")
  void shouldWriteNothingWhenThereIsNoAllocatableStock() {
    Demand demand = pendingDemand("SKU-1", 5);
    givenAllocatableBatches(demand);

    // 「一批都沒有」與「有批但全部過期」的差別屬於庫存狀態，由庫存頁每次重算；訂單這一側
    // 不再為了那個區別多付一次查詢，因為它算出來也沒有不會過期的地方可以放。
    assertThat(assigner.assign(demand, movesFor(demand), now))
        .isEqualTo(AllocationOutcome.NO_ALLOCATABLE_STOCK);
    verify(stockPoolRepository, never()).save(any());
    verifyNoInteractions(stockMoveRepository, eventPublisher);
  }

  @Test
  @DisplayName("喚醒時佇列是空的就不應寫入任何東西，連庫存都不必查")
  void shouldWriteNothingWhenThereAreNoWaitingMovements() {
    List<Demand> result = assigner.assignAll(List.of(), now);

    // 補貨本身已經在 usecase 的 upsert 寫進去了，這裡再存一次只是多一次無謂的寫入與衝突。
    assertThat(result).isEmpty();
    verifyNoInteractions(stockPoolRepository, stockMoveRepository, eventPublisher);
  }

  @Test
  @DisplayName("喚醒時應把搬運投影回需求，依單據分組並保住到達順序")
  void shouldProjectWaitingMovementsBackIntoDemandsGroupedByPicking() {
    UUID firstPicking = IdGenerator.nextId();
    UUID secondPicking = IdGenerator.nextId();
    UUID firstOrder = IdGenerator.nextId();
    UUID secondOrder = IdGenerator.nextId();
    StockMove first = MovementFixtures.waitingMove(
        firstPicking, "SKU-1", IdGenerator.nextId(), 3, createdAt);
    StockMove second = MovementFixtures.waitingMove(
        secondPicking, "SKU-1", IdGenerator.nextId(), 3, createdAt);
    when(stockPickingRepository.findByIds(any())).thenReturn(List.of(
        picking(firstPicking, firstOrder), picking(secondPicking, secondOrder)));
    StockPool batch = stockPool("SKU-1", 5);
    when(stockPoolRepository.findAllocatableBatchesBySku(
        DemandFixtures.OWNER_ID, DemandFixtures.LOCATION_ID, Set.of("SKU-1"), today))
        .thenReturn(Map.of("SKU-1", List.of(batch)));

    List<Demand> allocated = assigner.assignAll(List.of(first, second), now);

    // 5 件只餵得飽第一張；第二張在 FIFO 之下就此停住，而不是被跳過去換一張配得到的。
    assertThat(allocated).extracting(Demand::orderId).containsExactly(firstOrder);
    assertThat(first.getState()).isEqualTo(MoveState.ASSIGNED);
    assertThat(second.getState()).isEqualTo(MoveState.CONFIRMED);
    verify(eventPublisher).publishEvent(new OrderAllocationCompleted(firstOrder, now));
  }

  @Test
  @DisplayName("沒有訂單的單據不是待配需求——入庫的搬運不得被喚醒配貨")
  void shouldIgnoreMovementsWhosePickingHasNoOrder() {
    StockMove inbound = MovementFixtures.waitingMove(
        IdGenerator.nextId(), "SKU-1", null, 3, createdAt);
    // 入庫的單據沒有訂單——orderId 為空，而不是「查不到這張單據」。
    when(stockPickingRepository.findByIds(any()))
        .thenReturn(List.of(picking(inbound.getPickingId(), null)));

    assertThat(assigner.assignAll(List.of(inbound), now)).isEmpty();
    verifyNoInteractions(eventPublisher);
  }

  private StockPicking picking(UUID id, UUID orderId) {
    return new StockPicking(
        id, MovementFixtures.OUTBOUND_TYPE_ID, DemandFixtures.OWNER_ID, orderId,
        DemandFixtures.LOCATION_ID, MovementFixtures.CUSTOMERS_LOCATION_ID);
  }

  private Demand pendingDemand(String skuCode, int quantity) {
    return DemandFixtures.demand(IdGenerator.nextId(), skuCode, quantity);
  }

  /** 這張需求的每一條行都已經有一段還在等貨的搬運——收單就建好了。 */
  private List<StockMove> movesFor(Demand demand) {
    UUID pickingId = IdGenerator.nextId();
    List<StockMove> moves = new ArrayList<>();
    demand.lines().forEach(line -> moves.add(MovementFixtures.waitingMove(
        pickingId, line.skuCode(), line.orderLineId(), line.quantity(), createdAt)));
    return moves;
  }

  /** 依批自己的 {@code skuCode} 分組，並替這張單指名卻一批都沒有的 SKU 補上空群組。 */
  private void givenAllocatableBatches(Demand demand, StockPool... batches) {
    Map<String, List<StockPool>> bySku = new java.util.LinkedHashMap<>();
    demand.totalsBySku().keySet().forEach(skuCode -> bySku.put(skuCode, new ArrayList<>()));
    for (StockPool batch : batches) {
      bySku.computeIfAbsent(batch.getSkuCode(), key -> new ArrayList<>()).add(batch);
    }
    when(stockPoolRepository.findAllocatableBatchesBySku(
        demand.ownerId(), demand.locationId(), demand.totalsBySku().keySet(), today))
        .thenReturn(bySku);
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

  private StockPool stockPool(String skuCode, int onHandQuantity) {
    return StockFixtures.unexpiredBatch(skuCode, onHandQuantity, 0);
  }
}
