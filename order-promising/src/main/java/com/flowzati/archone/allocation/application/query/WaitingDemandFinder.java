package com.flowzati.archone.allocation.application.query;

import com.flowzati.archone.allocation.domain.model.Demand;
import com.flowzati.archone.allocation.domain.model.DemandLine;
import com.flowzati.archone.allocation.domain.model.StockMove;
import com.flowzati.archone.allocation.domain.repository.StockMoveRepository;
import com.flowzati.archone.allocation.domain.repository.StockPickingRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 待配佇列：把「還在等貨的搬運」投影成配貨演算法看得懂的 {@link Demand}。
 *
 * <p><b>這一層存在的理由是讓演算法不必改。</b> 佇列的來源從「訂單與預留 join 出來的檢視」
 * 換成了「搬運的狀態」，但 {@code AllocationService} 的整籃判斷、FEFO 取批、head-of-line
 * blocking、批次上限全部依賴 {@code Demand} 的形狀——投影回同一個形狀，那些一個字都不用動。
 *
 * <p>分組用 {@code pickingId}：一張單據就是一張單的全部搬運，而 ship-complete 判斷的單位
 * 正是一張單。訂單 id 只在這裡查一次（發事件要用），不參與分組。
 */
@Component
public class WaitingDemandFinder {

  private final StockMoveRepository stockMoveRepository;
  private final StockPickingRepository stockPickingRepository;

  public WaitingDemandFinder(
      StockMoveRepository stockMoveRepository,
      StockPickingRepository stockPickingRepository
  ) {
    this.stockMoveRepository = stockMoveRepository;
    this.stockPickingRepository = stockPickingRepository;
  }

  public List<Demand> findWaiting(UUID ownerId, UUID locationId, String skuCode, int limit) {
    List<StockMove> moves =
        stockMoveRepository.findWaitingInFifoOrder(ownerId, locationId, skuCode, limit);
    return toDemands(moves);
  }

  /** 一張單的全部搬運（不論狀態）投影成需求。收單即配的路徑用。 */
  public List<Demand> forPickings(List<UUID> pickingIds) {
    return toDemands(stockMoveRepository.findByPickingIds(pickingIds));
  }

  private List<Demand> toDemands(List<StockMove> moves) {
    if (moves.isEmpty()) {
      return List.of();
    }
    // LinkedHashMap 保住 FIFO：查詢已經依到達順序回來，分組不得打亂它。
    Map<UUID, List<StockMove>> byPicking = new LinkedHashMap<>();
    for (StockMove move : moves) {
      byPicking.computeIfAbsent(move.getPickingId(), key -> new ArrayList<>()).add(move);
    }
    Map<UUID, UUID> orderIds = stockPickingRepository.findOrderIdsByIds(byPicking.keySet());

    List<Demand> demands = new ArrayList<>();
    byPicking.forEach((pickingId, pickingMoves) -> {
      UUID orderId = orderIds.get(pickingId);
      if (orderId == null) {
        // 沒有訂單的單據是入庫（第三個 change），它不是待配需求。
        return;
      }
      StockMove first = pickingMoves.getFirst();
      List<DemandLine> lines = pickingMoves.stream()
          .filter(move -> move.getOrderLineId() != null)
          .map(move -> new DemandLine(
              move.getOrderLineId(), move.getSkuCode(), move.getDemandQuantity()))
          .toList();
      if (!lines.isEmpty()) {
        demands.add(new Demand(orderId, first.getOwnerId(), first.getFromLocationId(), lines));
      }
    });
    return List.copyOf(demands);
  }
}
