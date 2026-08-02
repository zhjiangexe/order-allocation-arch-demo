package com.flowzati.archone.stock.infrastructure.repository.jpa;

import com.flowzati.archone.stock.infrastructure.entity.StockMoveEntity;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JpaStockMoveRepository extends JpaRepository<StockMoveEntity, UUID> {

  /**
   * 待配佇列的第一段：**選單據**。
   *
   * <p>與舊的待配需求查詢同一個手法——先選出要處理哪幾張，再取那幾張的全部行。上限以「單據
   * 張數」計，而 ship-complete 判斷的單位也是一張單據，兩者的維度因此一致。
   *
   * <p>排序鍵是 {@code orderLineId}（UUID v7，等於到達順序），與舊佇列相同。
   *
   * <p><b>分組用 pickingId 而不是 orderId</b>：單表，沿 {@code idx_stock_moves_waiting} 取列，
   * 這條熱路徑因此沒有 join。
   */
  @Query("""
      SELECT m.pickingId
        FROM StockMoveEntity m
       WHERE m.state = com.flowzati.archone.stock.domain.model.MoveState.CONFIRMED
         AND m.ownerId = :ownerId
         AND m.fromLocationId = :locationId
         AND m.skuCode = :skuCode
       GROUP BY m.pickingId
       ORDER BY MIN(m.orderLineId)
      """)
  List<UUID> findWaitingPickingIdsInFifoOrder(
      @Param("ownerId") UUID ownerId,
      @Param("locationId") UUID locationId,
      @Param("skuCode") String skuCode,
      Limit limit);

  /**
   * 第二段：**取那幾張單據的全部搬運**——含別的 SKU 的那些。
   *
   * <p>一張單整批配到或整批不配，所以判斷要看它全部的需求，不只命中補貨那個 SKU 的部分。
   */
  List<StockMoveEntity> findByPickingIdInOrderByOrderLineIdAsc(Collection<UUID> pickingIds);

  List<StockMoveEntity> findByOrderLineIdIn(Collection<UUID> orderLineIds);
}
