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

  /**
   * 找出 reconciliation 本輪值得嘗試的等待配貨 scope。
   *
   * <p>一個 scope 由 {@code ownerId + facilityId + locationId + skuCode} 定義；呼叫端會把每個
   * scope 轉成一個 {@code AllocateWaitingDemandCommand}，再交給真正的配貨 use case 執行。
   * 這個查詢只負責找候選 scope，不在這裡修改庫存、搬運或訂單狀態。
   *
   * <p>候選 scope 必須同時符合三個條件：
   * <ol>
   *   <li>存在訂單 outbound picking 的 {@code CONFIRMED} move，代表仍有等待配貨的需求；</li>
   *   <li>該 move 所在的 location 屬於一個 facility；</li>
   *   <li>同一個 owner、location、SKU 存在今天仍未過期且尚有可用數量的 stock pool。</li>
   * </ol>
   *
   * <p>{@code EXISTS} 只用來確認有可配庫存，避免一個 scope 因為多個 stock pool 批次而產生
   * 重複列；真正的 FEFO 取批與 ship-complete 判斷仍由配貨流程負責。結果依最早等待需求
   * 排序，{@code limit} 限制的是本輪要交給 application layer 的 scope 數量，不是 move 或
   * stock pool 數量。
   *
   * <p>這是提示性查詢，不是鎖定或保證：查詢完成後庫存可能被另一個 transaction 先配走，
   * 因此後續 use case 必須接受沒有成功配出的正常結果。
   */
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
  List<WaitingAllocationScopeView> findAllocatableWaitingScopes(@Param("today") LocalDate today, Limit limit);

  /**
   * 第二段：**取那幾張單據的全部搬運**——含別的 SKU 的那些。
   *
   * <p>一張單整批配到或整批不配，所以判斷要看它全部的需求，不只命中補貨那個 SKU 的部分。
   */
  List<StockMoveEntity> findByPickingIdInOrderByOrderLineIdAsc(Collection<UUID> pickingIds);

  List<StockMoveEntity> findByOrderLineIdIn(Collection<UUID> orderLineIds);
}
