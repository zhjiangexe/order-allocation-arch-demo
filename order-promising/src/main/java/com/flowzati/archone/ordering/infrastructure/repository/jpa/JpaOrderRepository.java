package com.flowzati.archone.ordering.infrastructure.repository.jpa;

import com.flowzati.archone.ordering.domain.model.OrderStatus;
import com.flowzati.archone.ordering.infrastructure.entity.OrderEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaOrderRepository extends JpaRepository<OrderEntity, UUID> {

  /**
   * 待配佇列：篩選鍵（貨主與 SKU）在行、排序鍵在 header，因此查詢跨兩張表。
   *
   * <p>欄位順序與 {@code idx_order_lines_backorder_fifo (owner_id, sku_code,
   * backordered_since, id)} 一致——等值篩選在前、排序鍵其次。
   */
  List<OrderEntity> findByLines_OwnerIdAndLines_SkuCodeAndStatusOrderByBackorderedSinceAscIdAsc(
      UUID ownerId,
      String skuCode,
      OrderStatus status
  );

  List<OrderEntity> findAllByOrderByPlacedAtDescIdDesc(Limit limit);
}
