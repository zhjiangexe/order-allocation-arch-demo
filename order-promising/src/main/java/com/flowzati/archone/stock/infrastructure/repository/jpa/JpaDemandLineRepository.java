package com.flowzati.archone.stock.infrastructure.repository.jpa;

import com.flowzati.archone.stock.infrastructure.entity.DemandLineEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * {@code demand_lines} view 的查詢。**只有讀。**
 *
 * <p><b>只剩下「這一張單」這一支。</b>依 {@code (貨主, 位置, SKU)} 選單、以及配套的取行查詢
 * 都已移除——待配佇列改由還在等貨的搬運回答（{@code WaitingDemandFinder}）。這個 view 現在
 * 只回答一件事：**這張單有哪些行還沒被執行層接手**。
 */
public interface JpaDemandLineRepository extends JpaRepository<DemandLineEntity, UUID> {

  List<DemandLineEntity> findByOrderIdOrderByOrderLineIdAsc(UUID orderId);
}
