package com.flowzati.archone.stock.application.movement;

import com.flowzati.archone.stock.domain.model.MoveState;
import com.flowzati.archone.stock.domain.model.PickingState;
import com.flowzati.archone.stock.domain.model.StockFixtures;
import com.flowzati.archone.stock.domain.model.StockMove;
import com.flowzati.archone.stock.domain.model.StockMoveLine;
import com.flowzati.archone.stock.domain.model.StockPicking;
import com.flowzati.archone.stock.domain.model.StockPool;
import com.flowzati.archone.stock.domain.repository.StockMoveRepository;
import com.flowzati.archone.stock.domain.repository.StockPickingRepository;
import com.flowzati.archone.stock.domain.repository.StockPoolRepository;
import com.flowzati.archone.foundation.identity.IdGenerator;
import com.flowzati.archone.testsupport.MovementFixtures;
import com.flowzati.archone.testsupport.OrderFixtures;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("取消搬運")
class MovementCancellerTest {

  private static final LocalDate NEAR_EXPIRY = LocalDate.of(2026, 8, 31);
  private static final LocalDate FAR_EXPIRY = LocalDate.of(2027, 1, 31);

  private final Instant now = Instant.parse("2026-07-24T01:00:00Z");
  private final Instant createdAt = now.minusSeconds(60);

  private StockMoveRepository stockMoveRepository;
  private StockPickingRepository stockPickingRepository;
  private StockPoolRepository stockPoolRepository;
  private MovementCanceller canceller;

  @BeforeEach
  void setUp() {
    stockMoveRepository = mock(StockMoveRepository.class);
    stockPickingRepository = mock(StockPickingRepository.class);
    stockPoolRepository = mock(StockPoolRepository.class);
    canceller = new MovementCanceller(
        stockMoveRepository, stockPickingRepository, stockPoolRepository);
  }

  @Test
  @DisplayName("釋放一張單的多條明細時應全部釋放，搬運轉為取消、明細刪除")
  void shouldReleaseEveryLineOfAnOrder() {
    UUID orderId = IdGenerator.nextId();
    StockPicking picking = givenAPicking(orderId);
    StockMove move = MovementFixtures.assignedMove(
        picking.id(), "SKU-1", IdGenerator.nextId(), 80, createdAt);
    StockPool near = StockFixtures.batchExpiringOn("SKU-1", NEAR_EXPIRY, 60, 60);
    StockPool far = StockFixtures.batchExpiringOn("SKU-1", FAR_EXPIRY, 40, 20);
    givenMovesWithLines(picking, List.of(move),
        new StockMoveLine(IdGenerator.nextId(), move.getId(), near.getId(), 60),
        new StockMoveLine(IdGenerator.nextId(), move.getId(), far.getId(), 20));
    givenBatches(near, far);

    boolean released = canceller.cancelForOrder(orderId);

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
    assertThat(picking.state()).isEqualTo(PickingState.CANCELLED);
    verify(stockPickingRepository).save(picking);
  }

  @Test
  @DisplayName("批次寫入應依 (SKU, 效期, 入庫日, id) 排序，不得跟著明細的順序走")
  void shouldWriteBatchesInTheGlobalOrderRegardlessOfLineOrder() {
    UUID orderId = IdGenerator.nextId();
    StockPicking picking = givenAPicking(orderId);
    StockMove move = MovementFixtures.assignedMove(
        picking.id(), "SKU-1", IdGenerator.nextId(), 80, createdAt);
    StockPool near = StockFixtures.batchExpiringOn("SKU-1", NEAR_EXPIRY, 60, 60);
    StockPool far = StockFixtures.batchExpiringOn("SKU-1", FAR_EXPIRY, 40, 20);
    // 刻意把遠效期的明細放前面。取消的集合來源與配貨完全不同（它來自明細，不是 FEFO 查詢），
    // 所以排序不能靠「碰巧」——兩條路徑以相反順序鎖同一組列就是死鎖。
    givenMovesWithLines(picking, List.of(move),
        new StockMoveLine(IdGenerator.nextId(), move.getId(), far.getId(), 20),
        new StockMoveLine(IdGenerator.nextId(), move.getId(), near.getId(), 60));
    givenBatches(near, far);

    canceller.cancelForOrder(orderId);

    ArgumentCaptor<StockPool> captor = ArgumentCaptor.forClass(StockPool.class);
    verify(stockPoolRepository, org.mockito.Mockito.times(2)).save(captor.capture());
    assertThat(captor.getAllValues()).containsExactly(near, far);
  }

  @Test
  @DisplayName("多條明細釋放同一批時只應讀取一次庫存")
  void shouldLoadEachReleasedBatchOnlyOnce() {
    UUID orderId = IdGenerator.nextId();
    StockPicking picking = givenAPicking(orderId);
    StockMove move = MovementFixtures.assignedMove(
        picking.id(), "SKU-1", IdGenerator.nextId(), 30, createdAt);
    StockPool batch = StockFixtures.unexpiredBatch("SKU-1", 60, 30);
    givenMovesWithLines(picking, List.of(move),
        new StockMoveLine(IdGenerator.nextId(), move.getId(), batch.getId(), 20),
        new StockMoveLine(IdGenerator.nextId(), move.getId(), batch.getId(), 10));
    givenBatches(batch);

    canceller.cancelForOrder(orderId);

    verify(stockPoolRepository).findByIds(List.of(batch.getId()));
    assertThat(batch.getReservedQuantity()).isZero();
  }

  @Test
  @DisplayName("這張單沒有作業單時應為 no-op——沒有東西要取消")
  void shouldDoNothingWhenTheOrderHasNoPicking() {
    UUID orderId = IdGenerator.nextId();
    when(stockPickingRepository.findByOrderId(orderId)).thenReturn(List.of());

    assertThat(canceller.cancelForOrder(orderId)).isFalse();
    verifyNoInteractions(stockMoveRepository, stockPoolRepository);
  }

  @Test
  @DisplayName("重複取消時應為 no-op 且不可重複增加 ATP")
  void shouldDoNothingWhenTheMovementsHaveAlreadyBeenCancelled() {
    UUID orderId = IdGenerator.nextId();
    StockPicking picking = givenAPicking(orderId);
    StockMove move = MovementFixtures.assignedMove(
        picking.id(), "SKU-1", IdGenerator.nextId(), 3, createdAt);
    StockPool batch = StockFixtures.unexpiredBatch("SKU-1", 10, 3);
    givenMovesWithLines(picking, List.of(move),
        new StockMoveLine(IdGenerator.nextId(), move.getId(), batch.getId(), 3));
    givenBatches(batch);
    canceller.cancelForOrder(orderId);

    // 第二次沒有明細可放——它們在第一次就被刪掉了，這正是「釋放是刪除」在重送下的樣子。
    when(stockMoveRepository.findLinesOf(any())).thenReturn(List.of());

    assertThat(canceller.cancelForOrder(orderId)).isFalse();
    assertThat(batch.getReservedQuantity()).isZero();
  }

  @Test
  @DisplayName("整張單都已出貨時不得取消，已離庫的量不可回到可用")
  void shouldNotTouchMovementsThatAreAlreadyDone() {
    UUID orderId = IdGenerator.nextId();
    StockPicking picking = givenAPicking(orderId);
    when(stockMoveRepository.findByPickingIds(List.of(picking.id())))
        .thenReturn(List.of(doneMove(picking.id())));

    // 取消一張已出貨的單是另一個問題（離倉後不得取消，R7）；這裡的責任只是不去碰它——
    // 把已離庫的量還回可用，庫存就會憑空多出一批賣得掉卻不存在的貨。
    assertThat(canceller.cancelForOrder(orderId)).isFalse();
    verifyNoInteractions(stockPoolRepository);
    verify(stockMoveRepository, never()).saveAll(any());
  }

  @Test
  @DisplayName("部分搬運已出貨時只取消未出貨搬運，且不得取消整張 picking")
  void shouldKeepPickingOpenWhenItContainsADoneMovement() {
    UUID orderId = IdGenerator.nextId();
    StockPicking picking = givenAPicking(orderId);
    StockMove departed = doneMove(picking.id());
    StockMove assigned = MovementFixtures.assignedMove(
        picking.id(), "SKU-2", IdGenerator.nextId(), 3, createdAt);
    StockPool batch = StockFixtures.unexpiredBatch("SKU-2", 10, 3);
    StockMoveLine line =
        new StockMoveLine(IdGenerator.nextId(), assigned.getId(), batch.getId(), 3);
    when(stockMoveRepository.findByPickingIds(List.of(picking.id())))
        .thenReturn(List.of(departed, assigned));
    when(stockMoveRepository.findLinesOf(List.of(assigned.getId())))
        .thenReturn(List.of(line));
    givenBatches(batch);

    assertThat(canceller.cancelForOrder(orderId)).isTrue();

    assertThat(departed.getState()).isEqualTo(MoveState.DONE);
    assertThat(assigned.getState()).isEqualTo(MoveState.CANCELLED);
    assertThat(batch.getReservedQuantity()).isZero();
    assertThat(picking.state()).isEqualTo(PickingState.ASSIGNED);
    verify(stockPickingRepository, never()).save(picking);
  }

  @Test
  @DisplayName("釋放量超過已預留數量時應拒絕且批與搬運都保持不變")
  void shouldRejectInconsistentReleaseBeforeMutatingAnything() {
    UUID orderId = IdGenerator.nextId();
    StockPicking picking = givenAPicking(orderId);
    StockMove move = MovementFixtures.assignedMove(
        picking.id(), "SKU-1", IdGenerator.nextId(), 3, createdAt);
    StockPool batch = StockFixtures.unexpiredBatch("SKU-1", 10, 2);
    givenMovesWithLines(picking, List.of(move),
        new StockMoveLine(IdGenerator.nextId(), move.getId(), batch.getId(), 3));
    givenBatches(batch);

    assertThatThrownBy(() -> canceller.cancelForOrder(orderId))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Quantity to release cannot exceed reserved quantity");

    assertThat(move.getState()).isEqualTo(MoveState.ASSIGNED);
    assertThat(batch.getReservedQuantity()).isEqualTo(2);
    verify(stockPoolRepository, never()).save(any());
    verify(stockMoveRepository, never()).saveAll(any());
    verify(stockMoveRepository, never()).deleteLinesOf(any());
  }

  @Test
  @DisplayName("明細指向的批已不存在時應拒絕，不可靜默略過")
  void shouldRejectALineWhoseBatchNoLongerExists() {
    UUID orderId = IdGenerator.nextId();
    StockPicking picking = givenAPicking(orderId);
    StockMove move = MovementFixtures.assignedMove(
        picking.id(), "SKU-1", IdGenerator.nextId(), 3, createdAt);
    UUID missing = UUID.randomUUID();
    givenMovesWithLines(picking, List.of(move),
        new StockMoveLine(IdGenerator.nextId(), move.getId(), missing, 3));
    when(stockPoolRepository.findByIds(List.of(missing))).thenReturn(List.of());

    // 這是資料損毀，不是正常缺席：明細的外鍵指向 stock_pools，指不到就是有東西壞了。
    assertThatThrownBy(() -> canceller.cancelForOrder(orderId))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no longer exists");
  }

  private StockPicking givenAPicking(UUID orderId) {
    StockPicking picking = new StockPicking(
        IdGenerator.nextId(),
        MovementFixtures.OUTBOUND_TYPE_ID,
        OrderFixtures.OWNER_ID,
        orderId,
        OrderFixtures.LOCATION_ID,
        MovementFixtures.CUSTOMERS_LOCATION_ID,
        OrderFixtures.DISPATCH_BY,
        OrderFixtures.RELEASE_PRIORITY,
        PickingState.ASSIGNED,
        null);
    when(stockPickingRepository.findByOrderId(orderId)).thenReturn(List.of(picking));
    return picking;
  }

  private void givenMovesWithLines(
      StockPicking picking, List<StockMove> moves, StockMoveLine... lines) {
    when(stockMoveRepository.findByPickingIds(List.of(picking.id()))).thenReturn(moves);
    when(stockMoveRepository.findLinesOf(moves.stream().map(StockMove::getId).toList()))
        .thenReturn(List.of(lines));
  }

  private void givenBatches(StockPool... batches) {
    List<StockPool> available = List.of(batches);
    when(stockPoolRepository.findByIds(any())).thenAnswer(invocation -> {
      Collection<UUID> requested = invocation.getArgument(0);
      return available.stream()
          .filter(batch -> requested.contains(batch.getId()))
          .toList();
    });
  }

  /** 一段已完成的搬運。貨已離庫，取消碰不得。 */
  private StockMove doneMove(UUID pickingId) {
    return new StockMove(
        IdGenerator.nextId(), pickingId, OrderFixtures.OWNER_ID, "SKU-1",
        OrderFixtures.LOCATION_ID, MovementFixtures.CUSTOMERS_LOCATION_ID,
        IdGenerator.nextId(), 5, MoveState.DONE, createdAt, createdAt, null);
  }

  @SuppressWarnings("unchecked")
  private List<StockMove> savedMoves() {
    ArgumentCaptor<Collection<StockMove>> captor = ArgumentCaptor.forClass(Collection.class);
    verify(stockMoveRepository).saveAll(captor.capture());
    return List.copyOf(captor.getValue());
  }
}
