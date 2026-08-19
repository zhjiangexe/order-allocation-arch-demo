package com.flowzati.archone.stock.movement.infrastructure.repository.jpa;

import com.flowzati.archone.stock.movement.infrastructure.entity.StockPickingEntity;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaStockPickingRepository extends JpaRepository<StockPickingEntity, UUID> {

  List<StockPickingEntity> findByOrderId(UUID orderId);

  List<StockPickingEntity> findByIdIn(Collection<UUID> ids);
}
