package com.flowzati.archone.allocation.infrastructure.repository.jpa;

import com.flowzati.archone.allocation.infrastructure.entity.StockPoolEntity;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JpaStockRepository extends JpaRepository<StockPoolEntity, UUID> {

  /**
   * FEFO 順序：效期近的先出，同效期則先進先出，最後以 id 定序。
   *
   * <p>兩個篩選條件都在資料庫層：**沒過期**，而且**還有量**。前者若載進記憶體再丟，會隨著
   * 過期批的累積愈來愈浪費，而它們只增不減；後者則是因為全被預留光的批對配貨而言等於不
   * 存在，載回來只會讓每個呼叫端各自記得跳過它。
   *
   * <p>寫成 JPQL 而不是衍生查詢名，是因為 {@code onHandQuantity > reservedQuantity} 是欄位
   * 對欄位的比較，方法名的語法表達不出來。
   */
  @Query("""
      SELECT b FROM StockPoolEntity b
      WHERE b.ownerId = :ownerId
        AND b.nodeId = :nodeId
        AND b.skuCode = :skuCode
        AND b.expiryDate >= :today
        AND b.onHandQuantity > b.reservedQuantity
      ORDER BY b.expiryDate ASC, b.inDate ASC, b.id ASC
      """)
  List<StockPoolEntity> findAllocatableBatchesInFefoOrder(
      @Param("ownerId") UUID ownerId,
      @Param("nodeId") UUID nodeId,
      @Param("skuCode") String skuCode,
      @Param("today") LocalDate today);

  /** 庫存頁用：不篩選，過期與預留光的都要在。 */
  List<StockPoolEntity> findByOwnerIdAndSkuCodeOrderByNodeIdAscExpiryDateAscInDateAscIdAsc(
      UUID ownerId, String skuCode);

  Optional<StockPoolEntity> findByOwnerIdAndNodeIdAndSkuCodeAndInDateAndExpiryDate(
      UUID ownerId, UUID nodeId, String skuCode, LocalDate inDate, LocalDate expiryDate);
}
