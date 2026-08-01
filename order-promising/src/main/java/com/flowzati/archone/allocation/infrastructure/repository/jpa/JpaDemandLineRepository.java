package com.flowzati.archone.allocation.infrastructure.repository.jpa;

import com.flowzati.archone.allocation.infrastructure.entity.DemandLineEntity;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * {@code demand_lines} view 的查詢。**只有讀。**
 *
 * <p>佇列查詢刻意分兩段，理由在 {@link #findOrderIdsWithOutstandingDemand} 上。
 */
public interface JpaDemandLineRepository extends JpaRepository<DemandLineEntity, UUID> {

  /**
   * 第一段——**選單**：哪些訂單有還欠的行命中這個 {@code (貨主, 倉, SKU)}，依到達順序取前 N 張。
   *
   * <p>排序鍵是 {@code orderId}，UUID v7，時間戳編在主鍵裡，所以它的大小順序就是訂單進入系統
   * 的順序。不需要時間欄位，也不需要 tie-breaker——主鍵本身唯一。
   *
   * <p>回的是識別碼而不是行：這一段要決定的是「哪幾張單進入本輪」，取行是第二段的事。若在這裡
   * 直接取行，撈到的只會是命中該 SKU 的那些，而整籃判斷需要那些單的**每一條**行。
   */
  @Query("""
      SELECT DISTINCT d.orderId
        FROM DemandLineEntity d
       WHERE d.ownerId = :ownerId
         AND d.locationId = :locationId
         AND d.skuCode = :skuCode
       ORDER BY d.orderId
      """)
  List<UUID> findOrderIdsWithOutstandingDemand(
      @Param("ownerId") UUID ownerId,
      @Param("locationId") UUID locationId,
      @Param("skuCode") String skuCode,
      Limit limit);

  /**
   * 第二段——**取行**：第一段選中的那些訂單，各自還欠的**全部**行（含別的 SKU）。
   *
   * <p>查的仍是 {@code demand_lines}，**不 join {@code order_lines}**：那會讓 allocation 的
   * 查詢出現該表名，而架構測試禁止它。view 自己就拿得到同樣的東西。
   *
   * <p>排序保證同一張單的行相鄰且穩定，映射時才能一次摺出一個 {@code Demand}。
   */
  List<DemandLineEntity> findByOrderIdInOrderByOrderIdAscOrderLineIdAsc(
      Collection<UUID> orderIds);

  List<DemandLineEntity> findByOrderIdOrderByOrderLineIdAsc(UUID orderId);
}
