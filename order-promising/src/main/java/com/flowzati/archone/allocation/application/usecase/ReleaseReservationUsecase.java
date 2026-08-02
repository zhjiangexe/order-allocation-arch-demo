package com.flowzati.archone.allocation.application.usecase;

import com.flowzati.archone.allocation.application.command.ReleaseReservationCommand;
import com.flowzati.archone.allocation.application.coordinator.OrderAllocationCoordinator;
import com.flowzati.archone.allocation.domain.model.MoveState;
import com.flowzati.archone.allocation.domain.model.StockMove;
import com.flowzati.archone.allocation.domain.model.StockMoveLine;
import com.flowzati.archone.allocation.domain.model.StockPicking;
import com.flowzati.archone.allocation.domain.model.StockPool;
import com.flowzati.archone.allocation.domain.repository.StockMoveRepository;
import com.flowzati.archone.allocation.domain.repository.StockPickingRepository;
import com.flowzati.archone.allocation.domain.repository.StockPoolRepository;
import com.flowzati.archone.common.inbox.InboxRepo;
import com.flowzati.archone.common.inbox.InboundCommand;
import jakarta.transaction.Transactional;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 取消一張單：把它的搬運取消，鎖住的量還給庫存。
 *
 * <p>類別名還叫 {@code ReleaseReservation}，而預留已經不是一個獨立的東西了——它是一段搬運
 * 被鎖定的狀態。改名連同 {@code ReleaseReservationCommand} 與對外事件的處理鏈一起，排在第四
 * 個 change 的命名收斂。
 */
@Service
public class ReleaseReservationUsecase {

  private final InboxRepo inboxRepo;
  private final StockMoveRepository stockMoveRepository;
  private final StockPickingRepository stockPickingRepository;
  private final StockPoolRepository stockPoolRepository;
  private final OrderAllocationCoordinator allocationCoordinator;
  private final Clock clock;

  public ReleaseReservationUsecase(
      InboxRepo inboxRepo,
      StockMoveRepository stockMoveRepository,
      StockPickingRepository stockPickingRepository,
      StockPoolRepository stockPoolRepository,
      OrderAllocationCoordinator allocationCoordinator,
      Clock clock) {
    this.inboxRepo = inboxRepo;
    this.stockMoveRepository = stockMoveRepository;
    this.stockPickingRepository = stockPickingRepository;
    this.stockPoolRepository = stockPoolRepository;
    this.allocationCoordinator = allocationCoordinator;
    this.clock = clock;
  }

  @Transactional
  public void handle(InboundCommand<ReleaseReservationCommand> inbound) {
    if (!inboxRepo.claimIfNew(inbound.message())) {
      return;
    }
    ReleaseReservationCommand command = inbound.command();

    // 以訂單找它的作業單，再取那些單據的全部搬運。單據記著 order_id，所以執行層自己回答得了
    // 「這張單有哪些搬運」——不必為此去讀 ordering 的表。
    //
    // 也不能改查 demand_lines：那個 view 現在回答的是「哪些行還沒被接手」，而要取消的恰恰是
    // **已經被接手**的那些，它們早就從 view 裡消失了。
    List<UUID> pickingIds = stockPickingRepository.findByOrderId(command.orderId()).stream()
        .map(StockPicking::id)
        .toList();
    if (pickingIds.isEmpty()) {
      return;
    }

    // 已完成的不動：貨已經離庫，取消不該把它變回可用量。取消一張已出貨的單是另一個問題
    // （R7 的「離倉後不得取消」），這裡只是不去碰它。
    List<StockMove> moves = stockMoveRepository.findByPickingIds(pickingIds).stream()
        .filter(move -> move.getState() != MoveState.DONE)
        .toList();
    if (moves.isEmpty()) {
      return;
    }

    // 一條行跨三批就有三條明細，全部都要放。只放第一條的話其餘批的量會永遠鎖著，而且不會有
    // 任何錯誤浮現——庫存看起來只是莫名其妙少了一些。
    List<StockMoveLine> lines =
        stockMoveRepository.findLinesOf(moves.stream().map(StockMove::getId).toList());

    Map<UUID, StockPool> batchesById = new LinkedHashMap<>();
    for (StockMoveLine line : lines) {
      batchesById.computeIfAbsent(line.stockPoolId(), id -> stockPoolRepository.findById(id)
          .orElseThrow(() -> new IllegalStateException("Stock pool " + id + " no longer exists")));
    }

    allocationCoordinator.releaseMoves(moves, lines, batchesById, clock.instant());
  }
}
