package com.flowzati.archone.allocation.application.movement;

import com.flowzati.archone.allocation.domain.model.MoveState;
import com.flowzati.archone.allocation.domain.model.StockMove;
import com.flowzati.archone.allocation.domain.model.StockMoveLine;
import com.flowzati.archone.allocation.domain.model.StockPicking;
import com.flowzati.archone.allocation.domain.model.StockPool;
import com.flowzati.archone.allocation.domain.model.StockWriteOrder;
import com.flowzati.archone.allocation.domain.repository.StockMoveRepository;
import com.flowzati.archone.allocation.domain.repository.StockPickingRepository;
import com.flowzati.archone.allocation.domain.repository.StockPoolRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
  public boolean cancelFor(UUID orderId, Instant cancelledAt) {
    List<UUID> pickingIds = stockPickingRepository.findByOrderId(orderId).stream()
        .map(StockPicking::id)
        .toList();
    if (pickingIds.isEmpty()) {
      return false;
    }

    // 已完成的不動：貨已經離庫，取消不該把它變回可用量。取消一張已出貨的單是另一個問題
    // （R7 的「離倉後不得取消」），這裡只是不去碰它。
    List<StockMove> moves = stockMoveRepository.findByPickingIds(pickingIds).stream()
        .filter(move -> move.getState() != MoveState.DONE)
        .toList();
    if (moves.isEmpty()) {
      return false;
    }

    // 一條行跨三批就有三條明細，全部都要放。只放第一條的話其餘批的量會永遠鎖著，而且不會有
    // 任何錯誤浮現——庫存看起來只是莫名其妙少了一些。
    List<StockMoveLine> lines =
        stockMoveRepository.findLinesOf(moves.stream().map(StockMove::getId).toList());

    Map<UUID, StockPool> touched = new LinkedHashMap<>();
    for (StockMoveLine line : lines) {
      StockPool batch = touched.computeIfAbsent(line.stockPoolId(), id ->
          stockPoolRepository.findById(id).orElseThrow(() ->
              new IllegalStateException("Stock pool " + id + " no longer exists")));
      if (line.quantity() > batch.getReservedQuantity()) {
        throw new IllegalArgumentException("Quantity to release cannot exceed reserved quantity");
      }
      batch.release(line.quantity());
    }

    List<StockMove> cancelled = moves.stream().filter(StockMove::cancel).toList();
    if (cancelled.isEmpty() && touched.isEmpty()) {
      return false;
    }

    touched.values().stream().sorted(StockWriteOrder.BY_GLOBAL_ORDER).forEach(stockPoolRepository::save);
    stockMoveRepository.deleteLinesOf(cancelled.stream().map(StockMove::getId).toList());
    stockMoveRepository.saveAll(cancelled);
    return true;
  }
}
