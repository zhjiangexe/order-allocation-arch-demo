package com.flowzati.archone.inventory.balance.infrastructure.repository.jpa;

import com.flowzati.archone.inventory.balance.infrastructure.entity.StockQuantEntity;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JpaStockQuantRepository extends JpaRepository<StockQuantEntity, UUID> {

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
      SELECT b FROM StockQuantEntity b
      WHERE b.ownerId = :ownerId
        AND b.locationId = :locationId
        AND b.skuCode = :skuCode
        AND b.expiryDate >= :today
        AND b.onHandQuantity > b.reservedQuantity
      ORDER BY b.expiryDate ASC, b.inDate ASC, b.id ASC
      """)
  List<StockQuantEntity> findAllocatableBatchesInFefoOrder(
      @Param("ownerId") UUID ownerId,
      @Param("locationId") UUID locationId,
      @Param("skuCode") String skuCode,
      @Param("today") LocalDate today);

  /**
   * 一次取多個 SKU 的可配批，仍是 FEFO 順序，且以 {@code skuCode} 為第一排序鍵讓同一個 SKU
   * 的批相鄰。
   *
   * <p><b>一次查而不是逐 SKU 查。</b>整籃判斷要看候選單的每一個 SKU，逐個查會是 N+1；更重要
   * 的是**死鎖**——本輪要碰哪些庫存列必須在進入交易前全部已知，`WRITE_ORDER` 的全序才算得
   * 出來，而邊查邊配的話那個集合要到配到一半才知道。
   *
   * <p>篩選條件與單 SKU 版本相同：沒過期，而且還有量。
   */
  @Query("""
      SELECT b FROM StockQuantEntity b
      WHERE b.ownerId = :ownerId
        AND b.locationId = :locationId
        AND b.skuCode IN :skuCodes
        AND b.expiryDate >= :today
        AND b.onHandQuantity > b.reservedQuantity
      ORDER BY b.skuCode ASC, b.expiryDate ASC, b.inDate ASC, b.id ASC
      """)
  List<StockQuantEntity> findAllocatableBatchesInFefoOrder(
      @Param("ownerId") UUID ownerId,
      @Param("locationId") UUID locationId,
      @Param("skuCodes") Collection<String> skuCodes,
      @Param("today") LocalDate today);

  /**
   * 庫存頁用：不篩選，過期與預留光的都要在。以 {@code skuCode} 為第一排序鍵讓同一個 SKU 的
   * 批相鄰，組內則是 FEFO——那正是配貨會取用它們的順序。
   */
  List<StockQuantEntity> findByOwnerIdAndLocationIdOrderBySkuCodeAscExpiryDateAscInDateAscIdAsc(
      UUID ownerId, UUID locationId);

  Optional<StockQuantEntity> findByOwnerIdAndLocationIdAndSkuCodeAndInDateAndExpiryDate(
      UUID ownerId, UUID locationId, String skuCode, LocalDate inDate, LocalDate expiryDate);
}
