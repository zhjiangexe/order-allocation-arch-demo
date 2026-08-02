package com.flowzati.archone.allocation.application.coordinator;

import com.flowzati.archone.allocation.domain.event.OrderAllocationCompleted;
import com.flowzati.archone.allocation.domain.model.MoveState;
import com.flowzati.archone.allocation.domain.model.StockMove;
import com.flowzati.archone.allocation.domain.model.StockMoveLine;
import com.flowzati.archone.allocation.domain.model.StockPool;
import com.flowzati.archone.allocation.domain.model.StockFixtures;
import com.flowzati.archone.allocation.domain.repository.StockMoveRepository;
import com.flowzati.archone.allocation.domain.repository.StockPoolRepository;
import com.flowzati.archone.allocation.domain.service.AllocationOutcome;
import com.flowzati.archone.allocation.domain.service.AllocationService;
import com.flowzati.archone.allocation.domain.model.Demand;
import com.flowzati.archone.common.IdGenerator;
import com.flowzati.archone.testsupport.DemandFixtures;
import com.flowzati.archone.testsupport.MovementFixtures;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OrderAllocationCoordinatorTest {

  private static final LocalDate NEAR_EXPIRY = LocalDate.of(2026, 8, 31);
  private static final LocalDate FAR_EXPIRY = LocalDate.of(2027, 1, 31);

  private final Instant now = Instant.parse("2026-07-23T00:00:00Z");
  private final Instant createdAt = now.minusSeconds(60);

  private StockPoolRepository stockPoolRepository;
  private StockMoveRepository stockMoveRepository;
  private ApplicationEventPublisher eventPublisher;
  private OrderAllocationCoordinator coordinator;

  @BeforeEach
  void setUp() {
    stockPoolRepository = mock(StockPoolRepository.class);
    stockMoveRepository = mock(StockMoveRepository.class);
    eventPublisher = mock(ApplicationEventPublisher.class);
    coordinator = new OrderAllocationCoordinator(
        new AllocationService(),
        stockPoolRepository,
        stockMoveRepository,
        eventPublisher
    );
  }

  @Test
  @DisplayName("配到貨時應把搬運轉為已鎖定、寫出明細，並統一儲存批次")
  void shouldAssignTheMovementAndWriteItsLinesWhenAllocationSucceeds() {
    Demand order = pendingDemand("SKU-1", 5);
    StockPool batch = stockPool("SKU-1", 10);
    StockMove move = givenAWaitingMovementFor(order);

    AllocationOutcome outcome = coordinator.allocateOrder(order, grouped(order, batch), now);

    assertThat(outcome).isEqualTo(AllocationOutcome.ALLOCATED);
    assertThat(batch.getReservedQuantity()).isEqualTo(5);
    verify(stockPoolRepository).save(batch);

    // 搬運是**轉狀態**而不是新建：它在收單時就存在了。時刻分成兩個——建立的時刻說它等了
    // 多久，鎖定的時刻說貨什麼時候到手，兩者回答不同的問題。
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
        new OrderAllocationCompleted(order.orderId(), now));
  }

  @Test
  @DisplayName("一條行吃到兩批時應產生兩條明細，數量加總等於該行的量")
  void shouldCreateOneMoveLinePerBatchTaken() {
    Demand order = pendingDemand("SKU-1", 80);
    StockPool near = StockFixtures.batchExpiringOn("SKU-1", NEAR_EXPIRY, 60, 0);
    StockPool far = StockFixtures.batchExpiringOn("SKU-1", FAR_EXPIRY, 40, 0);
    StockMove move = givenAWaitingMovementFor(order);

    coordinator.allocateOrder(order, grouped(order, near, far), now);

    // 粒度是行 × 批。摺成一條行一列會丟掉「哪一批是為哪一條行鎖的」，而出貨時要的正是它。
    // 這條性質原本由 stock_reservations 守著，換到 stock_move_lines 上必須原樣成立。
    List<StockMoveLine> lines = savedLines();
    assertThat(lines).hasSize(2);
    assertThat(lines).allSatisfy(line -> assertThat(line.moveId()).isEqualTo(move.getId()));
    assertThat(lines).map(StockMoveLine::stockPoolId)
        .containsExactly(near.getId(), far.getId());
    assertThat(lines.stream().mapToInt(StockMoveLine::quantity).sum()).isEqualTo(80);

    // 一條行對兩條明細，但搬運只有一段——它不會因為跨批而被寫兩次。
    assertThat(savedMoves()).containsExactly(move);
  }

  @Test
  @DisplayName("批次寫入應依 (SKU, 效期, 入庫日, id) 排序，不得跟著輸入順序走")
  void shouldWriteBatchesInADeterministicOrderRegardlessOfInputOrder() {
    Demand order = pendingDemand("SKU-1", 100);
    StockPool near = StockFixtures.batchExpiringOn("SKU-1", NEAR_EXPIRY, 60, 0);
    StockPool far = StockFixtures.batchExpiringOn("SKU-1", FAR_EXPIRY, 40, 0);
    givenAWaitingMovementFor(order);

    // 刻意把遠效期放前面。FEFO 查詢碰巧已經排好，但那是查詢的實作細節；釋放與補貨兩條路徑
    // 的集合來源完全不同，寫入順序必須自己保證，否則相反順序的兩個交易會互相等成死鎖。
    coordinator.allocateOrder(order, grouped(order, far, near), now);

    ArgumentCaptor<StockPool> captor = ArgumentCaptor.forClass(StockPool.class);
    verify(stockPoolRepository, org.mockito.Mockito.times(2)).save(captor.capture());
    assertThat(captor.getAllValues()).containsExactly(near, far);
  }

  @Test
  @DisplayName("配貨算出要動一條沒有搬運的行時應拋錯，不得靜默略過")
  void shouldRejectAPickForALineThatHasNoMovement() {
    Demand order = pendingDemand("SKU-1", 5);
    StockPool batch = stockPool("SKU-1", 10);
    // 佇列本來就是從搬運投影出來的，所以這不該發生。真的發生時代表兩者已經不同步，
    // 而靜默略過會讓庫存被鎖住卻沒有任何東西記得那是為誰鎖的。
    when(stockMoveRepository.findByOrderLineIds(org.mockito.ArgumentMatchers.any()))
        .thenReturn(List.of());

    assertThatThrownBy(() -> coordinator.allocateOrder(order, grouped(order, batch), now))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("No movement exists for order line");
  }

  @Test
  @DisplayName("單筆訂單分配失敗時不應儲存未變更的批次")
  void shouldNotSaveStockPoolWhenAllocationFails() {
    Demand order = pendingDemand("SKU-1", 5);
    StockPool batch = stockPool("SKU-1", 2);

    AllocationOutcome outcome = coordinator.allocateOrder(order, grouped(order, batch), now);

    assertThat(outcome).isEqualTo(AllocationOutcome.INSUFFICIENT_ATP);
    assertThat(batch.getReservedQuantity()).isZero();
    verify(stockPoolRepository, never()).save(batch);
    verifyNoInteractions(stockMoveRepository, eventPublisher);
  }

  @Test
  @DisplayName("沒有任何可售批時不應寫入任何東西，也不應為了問「為什麼」再查一次庫存")
  void shouldWriteNothingWhenThereIsNoAllocatableStock() {
    Demand order = pendingDemand("SKU-1", 5);

    // 「一批都沒有」與「有批但全部過期」的差別屬於庫存狀態，由庫存頁每次重算；訂單這一側
    // 不再為了那個區別多付一次查詢，因為它算出來也沒有不會過期的地方可以放。
    assertThat(coordinator.allocateOrder(order, grouped(order), now))
        .isEqualTo(AllocationOutcome.NO_ALLOCATABLE_STOCK);
    verifyNoInteractions(stockPoolRepository, stockMoveRepository, eventPublisher);
  }

  @Test
  @DisplayName("ATP 不足時只發缺貨的事實，不寫任何表")
  void shouldBackorderOrderWhenAllocationIsInsufficient() {
    Demand order = pendingDemand("SKU-1", 5);

    coordinator.backorderOrder(order, now);

    // 發的是 allocation 自己的事實，不是 ordering 的 OrderBackordered——後者由 ordering 收到
    // 對外事件後才產生。監聽它會讓「發事件→改狀態→產生事件→又發事件」循環下去。
    verify(eventPublisher).publishEvent(
        new com.flowzati.archone.allocation.domain.event.OrderBackorderRecorded(
            order.orderId(), now));
    verifyNoInteractions(stockPoolRepository, stockMoveRepository);
  }

  @Test
  @DisplayName("喚醒時沒有任何 backorder 就不應寫入任何東西")
  void shouldWriteNothingWhenThereAreNoBackorders() {
    StockPool batch = stockPool("SKU-1", 10);

    List<Demand> result = coordinator.allocateBackorders(List.of(), Map.of("SKU-1", List.of(batch)), now);

    // 補貨本身已經在 usecase 的 upsert 寫進去了，這裡再存一次只是多一次無謂的寫入與衝突。
    assertThat(result).isEmpty();
    verifyNoInteractions(stockPoolRepository, stockMoveRepository, eventPublisher);
  }

  @Test
  @DisplayName("釋放一張單的多條明細時應全部釋放，搬運轉為取消、明細刪除")
  void shouldReleaseEveryLineOfAnOrder() {
    StockPool near = StockFixtures.batchExpiringOn("SKU-1", NEAR_EXPIRY, 60, 60);
    StockPool far = StockFixtures.batchExpiringOn("SKU-1", FAR_EXPIRY, 40, 20);
    StockMove move = assignedMove("SKU-1", 80);
    StockMoveLine onNear = new StockMoveLine(
        IdGenerator.nextId(), move.getId(), near.getId(), 60);
    StockMoveLine onFar = new StockMoveLine(
        IdGenerator.nextId(), move.getId(), far.getId(), 20);

    boolean released = coordinator.releaseMoves(
        List.of(move), List.of(onNear, onFar),
        Map.of(near.getId(), near, far.getId(), far), now);

    // 只放第一條的話，遠效期那 20 件會永遠鎖著，而且不會有任何錯誤浮現。
    assertThat(released).isTrue();
    assertThat(near.getReservedQuantity()).isZero();
    assertThat(far.getReservedQuantity()).isZero();
    verify(stockPoolRepository).save(near);
    verify(stockPoolRepository).save(far);

    // **明細是刪除，不是標記為已釋放。** 一條被釋放的明細不表達任何事實，留著它等於讓每個
    // 讀取端都要記得過濾。釋放的歷史留在搬運的狀態上——連同被清掉的鎖定時刻。
    assertThat(move.getState()).isEqualTo(MoveState.CANCELLED);
    assertThat(move.getAssignedAt()).isNull();
    verify(stockMoveRepository).deleteLinesOf(List.of(move.getId()));
    assertThat(savedMoves()).containsExactly(move);
    verifyNoInteractions(eventPublisher);
  }

  @Test
  @DisplayName("重複釋放時應為 no-op 且不可重複增加 ATP")
  void shouldDoNothingWhenTheMovementHasAlreadyBeenReleased() {
    StockPool batch = StockFixtures.unexpiredBatch("SKU-1", 10, 3);
    StockMove move = assignedMove("SKU-1", 3);
    StockMoveLine line = new StockMoveLine(
        IdGenerator.nextId(), move.getId(), batch.getId(), 3);
    Map<UUID, StockPool> batches = Map.of(batch.getId(), batch);
    coordinator.releaseMoves(List.of(move), List.of(line), batches, now);

    // 第二次沒有明細可放——它們在第一次就被刪掉了，這正是「釋放是刪除」在重送下的樣子。
    boolean releasedAgain =
        coordinator.releaseMoves(List.of(move), List.of(), batches, now.plusSeconds(1));

    assertThat(releasedAgain).isFalse();
    assertThat(batch.getReservedQuantity()).isZero();
  }

  @Test
  @DisplayName("釋放量超過已預留數量時應拒絕且批與搬運都保持不變")
  void shouldRejectInconsistentReleaseBeforeMutatingAnything() {
    StockPool batch = StockFixtures.unexpiredBatch("SKU-1", 10, 2);
    StockMove move = assignedMove("SKU-1", 3);
    StockMoveLine line = new StockMoveLine(
        IdGenerator.nextId(), move.getId(), batch.getId(), 3);

    assertThatThrownBy(() -> coordinator.releaseMoves(
        List.of(move), List.of(line), Map.of(batch.getId(), batch), now))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Quantity to release cannot exceed reserved quantity");

    assertThat(move.getState()).isEqualTo(MoveState.ASSIGNED);
    assertThat(batch.getReservedQuantity()).isEqualTo(2);
    verifyNoInteractions(stockPoolRepository, stockMoveRepository);
  }

  @Test
  @DisplayName("明細指向的批沒被帶進來時應拒絕，不可靜默略過")
  void shouldRejectALineWhoseBatchWasNotSupplied() {
    StockMove move = assignedMove("SKU-1", 3);
    StockMoveLine orphan = new StockMoveLine(
        IdGenerator.nextId(), move.getId(), UUID.randomUUID(), 3);

    assertThatThrownBy(
        () -> coordinator.releaseMoves(List.of(move), List.of(orphan), Map.of(), now))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /**
   * 這張單的每一條行都已經有一段還在等貨的搬運——收單就建好了。
   *
   * <p>配貨是去找它們並轉狀態，所以測試必須先讓它們存在；不存在是另一個測試在驗的錯誤路徑。
   */
  private StockMove givenAWaitingMovementFor(Demand demand) {
    UUID pickingId = IdGenerator.nextId();
    List<StockMove> moves = demand.lines().stream()
        .map(line -> MovementFixtures.waitingMove(
            pickingId, line.skuCode(), line.orderLineId(), line.quantity(), createdAt))
        .toList();
    when(stockMoveRepository.findByOrderLineIds(org.mockito.ArgumentMatchers.any()))
        .thenReturn(moves);
    return moves.getFirst();
  }

  private StockMove assignedMove(String skuCode, int quantity) {
    return MovementFixtures.assignedMove(
        IdGenerator.nextId(), skuCode, IdGenerator.nextId(), quantity, createdAt);
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

  private Demand pendingDemand(String sku, int quantity) {
    return DemandFixtures.demand(IdGenerator.nextId(), sku, quantity);
  }

  /** 依批自己的 {@code skuCode} 分組，並替這張單指名卻一批都沒有的 SKU 補上空群組。 */
  private static Map<String, List<StockPool>> grouped(Demand demand, StockPool... batches) {
    Map<String, List<StockPool>> bySku = new java.util.LinkedHashMap<>();
    demand.totalsBySku().keySet()
        .forEach(skuCode -> bySku.put(skuCode, new java.util.ArrayList<>()));
    for (StockPool batch : batches) {
      bySku.computeIfAbsent(batch.getSkuCode(), key -> new java.util.ArrayList<>()).add(batch);
    }
    return bySku;
  }

  private StockPool stockPool(String sku, int onHandQuantity) {
    return StockFixtures.unexpiredBatch(sku, onHandQuantity, 0);
  }
}
