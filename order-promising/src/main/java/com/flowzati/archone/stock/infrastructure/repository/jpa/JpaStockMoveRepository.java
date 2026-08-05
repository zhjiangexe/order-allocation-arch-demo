package com.flowzati.archone.stock.infrastructure.repository.jpa;

import com.flowzati.archone.stock.infrastructure.entity.StockMoveEntity;
import java.util.Collection;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JpaStockMoveRepository extends JpaRepository<StockMoveEntity, UUID> {

  interface WaitingAllocationScopeView {
    UUID getOwnerId();

    UUID getFacilityId();

    UUID getLocationId();

    String getSkuCode();
  }

  /**
   * 待配佇列的第一段：**選單據**。
   *
   * <p>與舊的待配需求查詢同一個手法——先選出要處理哪幾張，再取那幾張的全部行。上限以「單據
   * 張數」計，而 ship-complete 判斷的單位也是一張單據，兩者的維度因此一致。
   *
   * <p>排序鍵是 {@code orderLineId}（UUID v7，等於到達順序），與舊佇列相同。
   *
   * <p><b>分組用 pickingId 而不是 orderId</b>：但只有為訂單工作的 picking
   * 才是待配需求。獨立 move 與沒有 orderId 的 inbound picking 都不可以占用佇列上限。
   */
  @Query("""
      SELECT m.pickingId
        FROM StockMoveEntity m
       WHERE m.state = com.flowzati.archone.stock.domain.model.MoveState.CONFIRMED
         AND m.ownerId = :ownerId
         AND m.fromLocationId = :locationId
         AND m.skuCode = :skuCode
         AND m.pickingId IN (
               SELECT p.id
                 FROM StockPickingEntity p
                WHERE p.orderId IS NOT NULL
             )
       GROUP BY m.pickingId
       ORDER BY MIN(m.orderLineId)
      """)
  List<UUID> findWaitingPickingIdsInFifoOrder(
      @Param("ownerId") UUID ownerId,
      @Param("locationId") UUID locationId,
      @Param("skuCode") String skuCode,
      Limit limit);

  @Query("""
      SELECT m.ownerId AS ownerId,
             l.facilityId AS facilityId,
             m.fromLocationId AS locationId,
             m.skuCode AS skuCode
        FROM StockMoveEntity m, StockLocationEntity l
       WHERE m.state = com.flowzati.archone.stock.domain.model.MoveState.CONFIRMED
         AND l.id = m.fromLocationId
         AND l.facilityId IS NOT NULL
         AND m.pickingId IN (
               SELECT p.id
                 FROM StockPickingEntity p
                WHERE p.orderId IS NOT NULL
             )
         AND EXISTS (
               SELECT s.id
                 FROM StockPoolEntity s
                WHERE s.ownerId = m.ownerId
                  AND s.locationId = m.fromLocationId
                  AND s.skuCode = m.skuCode
                  AND s.expiryDate >= :today
                  AND s.onHandQuantity > s.reservedQuantity
             )
       GROUP BY m.ownerId, l.facilityId, m.fromLocationId, m.skuCode
       ORDER BY MIN(m.createdAt), MIN(m.orderLineId)
      """)
  List<WaitingAllocationScopeView> findAllocatableWaitingScopes(
      @Param("today") LocalDate today,
      Limit limit);

  /**
   * 第二段：**取那幾張單據的全部搬運**——含別的 SKU 的那些。
   *
   * <p>一張單整批配到或整批不配，所以判斷要看它全部的需求，不只命中補貨那個 SKU 的部分。
   */
  List<StockMoveEntity> findByPickingIdInOrderByOrderLineIdAsc(Collection<UUID> pickingIds);

  List<StockMoveEntity> findByOrderLineIdIn(Collection<UUID> orderLineIds);
}
