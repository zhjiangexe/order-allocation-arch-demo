package com.flowzati.archone.stock.application.movement;

import com.flowzati.archone.stock.domain.model.StockMove;
import com.flowzati.archone.stock.domain.model.StockMoveLine;
import com.flowzati.archone.stock.domain.model.StockPicking;
import com.flowzati.archone.stock.domain.model.StockPool;
import com.flowzati.archone.stock.domain.model.StockWriteOrder;
import com.flowzati.archone.stock.domain.repository.StockMoveRepository;
import com.flowzati.archone.stock.domain.repository.StockPickingRepository;
import com.flowzati.archone.stock.domain.repository.StockPoolRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 搬運的第四個動作：**取消**（Odoo 的 {@code stock.move._action_cancel}）。
 *
 * <p>它與鎖定完全不共用任何決策——沒有可承諾量的問題、沒有整籃判斷、不碰
 * {@code AllocationService}。那正是它從配貨的協調者裡分出來的理由。
 *
 * <p><b>明細是刪除，不是標記為已釋放。</b>一條被釋放的明細不表達任何事實：貨沒有動，也沒有被
 * 鎖住。留著它等於讓每個讀取端都要記得過濾。釋放的歷史留在搬運的狀態轉換上。
 */
@Component
public class MovementCanceller {

  private final StockMoveRepository stockMoveRepository;
  private final StockPickingRepository stockPickingRepository;
  private final StockPoolRepository stockPoolRepository;

  public MovementCanceller(
      StockMoveRepository stockMoveRepository,
      StockPickingRepository stockPickingRepository,
      StockPoolRepository stockPoolRepository) {
    this.stockMoveRepository = stockMoveRepository;
    this.stockPickingRepository = stockPickingRepository;
    this.stockPoolRepository = stockPoolRepository;
  }

  /**
   * 取消一張單的搬運，把鎖住的量還給庫存。
   *
   * <p>以訂單找它的作業單，再取那些單據的全部搬運。單據記著 order_id，所以執行層自己回答得了
   * 「這張單有哪些搬運」——不必為此去讀 ordering 的表。
   *
   * <p>也不能改查 {@code demand_lines}：那個 view 回答的是「哪些行還沒被接手」，而要取消的
   * 恰恰是**已經被接手**的那些，它們早就從 view 裡消失了。
   *
   * @return 這一次是否真的改變了什麼。重送時為 {@code false}
   */
  public boolean cancelForOrder(UUID orderId) {
    List<StockPicking> pickings = stockPickingRepository.findByOrderId(orderId);
    if (pickings.isEmpty()) {
      return false;
    }

    List<UUID> pickingIds = pickings.stream().map(StockPicking::id).toList();
    List<StockMove> allMovements = stockMoveRepository.findByPickingIds(pickingIds);
    List<StockMove> cancellableMovements = allMovements.stream()
        .filter(StockMove::canCancel)
        .toList();
    if (cancellableMovements.isEmpty()) {
      return false;
    }

    Map<UUID, StockPool> releasedBatches = releaseReservedQuantities(cancellableMovements);
    List<StockMove> cancelledMovements = cancellableMovements.stream()
        .filter(StockMove::cancel)
        .toList();
    if (cancelledMovements.isEmpty() && releasedBatches.isEmpty()) {
      return false;
    }

    persistCancellation(releasedBatches, cancelledMovements);
    cancelFullyCancelledPickings(pickings, allMovements);
    return true;
  }

  /** 將每條 move line 鎖住的量逐批歸還；同一批只讀取一次。 */
  private Map<UUID, StockPool> releaseReservedQuantities(List<StockMove> movements) {
    // 一條行跨三批就有三條明細，全部都要放。只放第一條的話其餘批的量會永遠鎖著，而且不會有
    // 任何錯誤浮現——庫存看起來只是莫名其妙少了一些。
    List<UUID> movementIds = movements.stream().map(StockMove::getId).toList();
    List<StockMoveLine> lines = stockMoveRepository.findLinesOf(movementIds);

    Map<UUID, StockPool> releasedBatches = lines.isEmpty() ? Map.of() : loadRequiredBatches(lines.stream()
        .map(StockMoveLine::stockPoolId)
        .distinct()
        .toList());
    for (StockMoveLine line : lines) {
      // 數量安全是 StockPool 的不變式；這裡只負責依 move line 將釋放動作導向正確批次。
      StockPool stockPool = releasedBatches.get(line.stockPoolId());
      stockPool.release(line.quantity());
    }
    return releasedBatches;
  }

  /** 一次載入所有明細引用的批次，且在改變任何數量前先確認沒有遺失資料。 */
  private Map<UUID, StockPool> loadRequiredBatches(List<UUID> requiredBatchIds) {
    Map<UUID, StockPool> batchesById = stockPoolRepository.findByIds(requiredBatchIds).stream()
        .collect(Collectors.toMap(
            StockPool::getId,
            Function.identity()));

    requiredBatchIds.stream()
        .filter(batchId -> !batchesById.containsKey(batchId))
        .findFirst()
        .ifPresent(batchId -> {
          throw new IllegalStateException("Stock pool " + batchId + " no longer exists");
        });
    return batchesById;
  }

  /**
   * 先依全域鎖順序寫回批次，再刪除已失效的配置明細並保存 movement 狀態，避免反向取得庫存鎖。
   */
  private void persistCancellation(
      Map<UUID, StockPool> releasedBatches,
      List<StockMove> cancelledMovements
  ) {
    releasedBatches.values().stream()
        .sorted(StockWriteOrder.BY_GLOBAL_ORDER)
        .forEach(stockPoolRepository::save);
    List<UUID> cancelledMovementIds = cancelledMovements.stream().map(StockMove::getId).toList();
    stockMoveRepository.deleteLinesOf(cancelledMovementIds);
    stockMoveRepository.saveAll(cancelledMovements);
  }

  /** 只有底下 moves 全部取消的單據才進 CANCELLED；含 DONE move 的單據不得被倒退。 */
  private void cancelFullyCancelledPickings(
      List<StockPicking> pickings,
      List<StockMove> movements
  ) {
    Map<UUID, List<StockMove>> movementsByPicking = movements.stream()
        .filter(move -> move.getPickingId() != null)
        .collect(Collectors.groupingBy(StockMove::getPickingId));

    for (StockPicking picking : pickings) {
      List<StockMove> pickingMoves = movementsByPicking.getOrDefault(picking.id(), List.of());
      if (!pickingMoves.isEmpty()
          && pickingMoves.stream().allMatch(StockMove::isCancelled)
          && picking.cancel()) {
        stockPickingRepository.save(picking);
      }
    }
  }
}
