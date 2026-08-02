package com.flowzati.archone.allocation.application.usecase;

import com.flowzati.archone.allocation.application.command.ReleaseReservationCommand;
import com.flowzati.archone.allocation.application.coordinator.OrderAllocationCoordinator;
import com.flowzati.archone.allocation.domain.model.MoveState;
import com.flowzati.archone.allocation.domain.model.StockMove;
import com.flowzati.archone.allocation.domain.model.StockMoveLine;
import com.flowzati.archone.allocation.domain.model.StockPicking;
import com.flowzati.archone.allocation.domain.model.StockPool;
import com.flowzati.archone.allocation.domain.model.StockFixtures;
import com.flowzati.archone.allocation.domain.repository.StockMoveRepository;
import com.flowzati.archone.allocation.domain.repository.StockPickingRepository;
import com.flowzati.archone.allocation.domain.repository.StockPoolRepository;
import com.flowzati.archone.common.IdGenerator;
import com.flowzati.archone.common.inbox.InboxRepo;
import com.flowzati.archone.common.inbox.InboundCommand;
import com.flowzati.archone.common.inbox.MessageMetadata;
import com.flowzati.archone.testsupport.MovementFixtures;
import com.flowzati.archone.testsupport.OrderFixtures;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ReleaseReservationUsecaseTest {

  private final Instant now = Instant.parse("2026-07-24T01:00:00Z");
  private final Instant createdAt = now.minusSeconds(60);

  private InboxRepo inboxRepo;
  private StockMoveRepository stockMoveRepository;
  private StockPickingRepository stockPickingRepository;
  private StockPoolRepository stockPoolRepository;
  private OrderAllocationCoordinator coordinator;
  private ReleaseReservationUsecase usecase;

  @BeforeEach
  void setUp() {
    inboxRepo = mock(InboxRepo.class);
    stockMoveRepository = mock(StockMoveRepository.class);
    stockPickingRepository = mock(StockPickingRepository.class);
    stockPoolRepository = mock(StockPoolRepository.class);
    coordinator = mock(OrderAllocationCoordinator.class);
    usecase = new ReleaseReservationUsecase(
        inboxRepo,
        stockMoveRepository,
        stockPickingRepository,
        stockPoolRepository,
        coordinator,
        Clock.fixed(now, ZoneId.of("UTC"))
    );
  }

  @Test
  @DisplayName("訊息已處理過時不應再執行釋放")
  void shouldDoNothingWhenMessageWasAlreadyHandled() {
    UUID messageId = UUID.randomUUID();
    when(inboxRepo.claimIfNew(message(messageId))).thenReturn(false);

    usecase.handle(inbound(UUID.randomUUID(), messageId));

    verifyNoInteractions(
        stockMoveRepository, stockPickingRepository, stockPoolRepository, coordinator);
  }

  @Test
  @DisplayName("這張單沒有作業單時應為合法 no-op——沒有東西要取消")
  void shouldDoNothingWhenTheOrderHasNoPicking() {
    UUID messageId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    when(inboxRepo.claimIfNew(message(messageId))).thenReturn(true);
    // 不先查訂單存不存在——作業單是 allocation 自己的資料，直接問它就好。訂單不存在、
    // 訂單存在但還沒被接手、搬運早就取消過，三種情形在這裡是同一件事：沒有東西要取消。
    when(stockPickingRepository.findByOrderId(orderId)).thenReturn(List.of());

    usecase.handle(inbound(orderId, messageId));

    verify(stockPickingRepository).findByOrderId(orderId);
    verifyNoInteractions(stockMoveRepository, stockPoolRepository, coordinator);
  }

  @Test
  @DisplayName("整張單都已出貨時不得取消，已離庫的量不可回到可用")
  void shouldNotTouchMovementsThatAreAlreadyDone() {
    UUID messageId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    StockPicking picking = givenAPicking(orderId);
    when(inboxRepo.claimIfNew(message(messageId))).thenReturn(true);
    when(stockMoveRepository.findByPickingIds(List.of(picking.id())))
        .thenReturn(List.of(doneMove(picking.id())));

    usecase.handle(inbound(orderId, messageId));

    // 取消一張已出貨的單是另一個問題（離倉後不得取消，R7）；這裡的責任只是不去碰它——
    // 把已離庫的量還回可用，庫存就會憑空多出一批賣得掉卻不存在的貨。
    verifyNoInteractions(stockPoolRepository, coordinator);
  }

  @Test
  @DisplayName("一條行跨兩批時應把兩條明細一起交給 Coordinator 釋放")
  void shouldReleaseEveryLineOfTheOrder() {
    UUID messageId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    StockPicking picking = givenAPicking(orderId);
    StockMove move = MovementFixtures.assignedMove(
        picking.id(), "SKU-1", IdGenerator.nextId(), 80, createdAt);
    StockPool near = StockFixtures.unexpiredBatch("SKU-1", 60, 60);
    StockPool far = StockFixtures.unexpiredBatch("SKU-1", 40, 20);
    StockMoveLine onNear = new StockMoveLine(
        IdGenerator.nextId(), move.getId(), near.getId(), 60);
    StockMoveLine onFar = new StockMoveLine(
        IdGenerator.nextId(), move.getId(), far.getId(), 20);
    when(inboxRepo.claimIfNew(message(messageId))).thenReturn(true);
    when(stockMoveRepository.findByPickingIds(List.of(picking.id()))).thenReturn(List.of(move));
    when(stockMoveRepository.findLinesOf(List.of(move.getId())))
        .thenReturn(List.of(onNear, onFar));
    when(stockPoolRepository.findById(near.getId())).thenReturn(Optional.of(near));
    when(stockPoolRepository.findById(far.getId())).thenReturn(Optional.of(far));

    usecase.handle(inbound(orderId, messageId));

    // 只釋放第一條的話，遠效期那 20 件會永遠鎖著，而且不會有任何錯誤浮現。
    verify(coordinator).releaseMoves(
        eq(List.of(move)),
        eq(List.of(onNear, onFar)),
        eq(Map.of(near.getId(), near, far.getId(), far)),
        eq(now));
  }

  @Test
  @DisplayName("明細指向的批不存在時應失敗")
  void shouldFailWhenAMoveLinePointsAtAMissingBatch() {
    UUID messageId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    StockPicking picking = givenAPicking(orderId);
    StockMove move = MovementFixtures.assignedMove(
        picking.id(), "SKU-1", IdGenerator.nextId(), 3, createdAt);
    UUID stockPoolId = UUID.randomUUID();
    when(inboxRepo.claimIfNew(message(messageId))).thenReturn(true);
    when(stockMoveRepository.findByPickingIds(List.of(picking.id()))).thenReturn(List.of(move));
    when(stockMoveRepository.findLinesOf(List.of(move.getId()))).thenReturn(
        List.of(new StockMoveLine(IdGenerator.nextId(), move.getId(), stockPoolId, 3)));
    when(stockPoolRepository.findById(stockPoolId)).thenReturn(Optional.empty());

    // 這是資料損毀，不是正常缺席：明細的外鍵指向 stock_pools，指不到就是有東西壞了。
    assertThatThrownBy(() -> usecase.handle(inbound(orderId, messageId)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Stock pool " + stockPoolId + " no longer exists");
    verifyNoInteractions(coordinator);
  }

  @Test
  @DisplayName("多條明細指向同一批時只應查一次庫存")
  void shouldLoadEachBatchOnlyOnce() {
    UUID messageId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    StockPicking picking = givenAPicking(orderId);
    StockMove move = MovementFixtures.assignedMove(
        picking.id(), "SKU-1", IdGenerator.nextId(), 30, createdAt);
    StockPool batch = StockFixtures.unexpiredBatch("SKU-1", 60, 30);
    when(inboxRepo.claimIfNew(message(messageId))).thenReturn(true);
    when(stockMoveRepository.findByPickingIds(List.of(picking.id()))).thenReturn(List.of(move));
    when(stockMoveRepository.findLinesOf(List.of(move.getId()))).thenReturn(List.of(
        new StockMoveLine(IdGenerator.nextId(), move.getId(), batch.getId(), 20),
        new StockMoveLine(IdGenerator.nextId(), move.getId(), batch.getId(), 10)));
    when(stockPoolRepository.findById(batch.getId())).thenReturn(Optional.of(batch));

    usecase.handle(inbound(orderId, messageId));

    verify(stockPoolRepository).findById(batch.getId());
  }

  /** 這張單在收單時就有一張作業單。取消要從它出發，而不是去讀 ordering 的表。 */
  private StockPicking givenAPicking(UUID orderId) {
    StockPicking picking = new StockPicking(
        IdGenerator.nextId(),
        MovementFixtures.OUTBOUND_TYPE_ID,
        OrderFixtures.OWNER_ID,
        OrderFixtures.LOCATION_ID,
        MovementFixtures.CUSTOMERS_LOCATION_ID);
    when(stockPickingRepository.findByOrderId(orderId)).thenReturn(List.of(picking));
    return picking;
  }

  /** 一段已完成的搬運。貨已離庫，取消碰不得。 */
  private StockMove doneMove(UUID pickingId) {
    return new StockMove(
        IdGenerator.nextId(),
        pickingId,
        OrderFixtures.OWNER_ID,
        "SKU-1",
        OrderFixtures.LOCATION_ID,
        MovementFixtures.CUSTOMERS_LOCATION_ID,
        IdGenerator.nextId(),
        5,
        MoveState.DONE,
        createdAt,
        createdAt,
        null);
  }

  private MessageMetadata message(UUID eventId) {
    return new MessageMetadata(eventId, "OrderCancelledIntegrationEvent");
  }

  private InboundCommand<ReleaseReservationCommand> inbound(UUID orderId, UUID eventId) {
    return new InboundCommand<>(new ReleaseReservationCommand(orderId), message(eventId));
  }
}
