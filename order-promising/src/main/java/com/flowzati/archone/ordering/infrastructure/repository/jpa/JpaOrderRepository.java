package com.flowzati.archone.ordering.infrastructure.repository.jpa;

import com.flowzati.archone.ordering.domain.model.OrderStatus;
import com.flowzati.archone.ordering.infrastructure.entity.OrderEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaOrderRepository extends JpaRepository<OrderEntity, UUID> {

  List<OrderEntity> findBySkuAndStatusOrderByBackorderedSinceAscIdAsc(
      String sku,
      OrderStatus status
  );

  List<OrderEntity> findAllByOrderByPlacedAtDescIdDesc(Limit limit);
}
