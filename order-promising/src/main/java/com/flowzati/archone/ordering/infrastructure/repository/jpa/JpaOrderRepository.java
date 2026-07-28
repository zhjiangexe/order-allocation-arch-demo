package com.flowzati.archone.ordering.infrastructure.repository.jpa;

import com.flowzati.archone.ordering.domain.model.OrderStatus;
import com.flowzati.archone.ordering.infrastructure.entity.OrderEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaOrderRepository extends JpaRepository<OrderEntity, UUID> {

  /**
   * 待配佇列：篩選鍵在行、排序鍵在 header，因此查詢跨兩張表。
   *
   * <p>尚未帶上貨主——補貨事件目前只帶 SKU，沒有呼叫端拿得出 {@code ownerId}。讓事件帶上
   * 貨主、並把佇列真正按貨主分開，是後續任務。
   */
  List<OrderEntity> findByLines_SkuCodeAndStatusOrderByBackorderedSinceAscIdAsc(
      String skuCode,
      OrderStatus status
  );

  List<OrderEntity> findAllByOrderByPlacedAtDescIdDesc(Limit limit);
}
