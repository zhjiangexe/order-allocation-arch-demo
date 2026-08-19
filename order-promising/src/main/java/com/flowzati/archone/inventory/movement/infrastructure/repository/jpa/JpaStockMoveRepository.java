package com.flowzati.archone.inventory.movement.infrastructure.repository.jpa;

import com.flowzati.archone.inventory.movement.infrastructure.entity.StockMoveEntity;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaStockMoveRepository extends JpaRepository<StockMoveEntity, UUID> {

  List<StockMoveEntity> findByPickingIdInOrderByOrderLineIdAsc(Collection<UUID> pickingIds);

  List<StockMoveEntity> findByOrderLineIdIn(Collection<UUID> orderLineIds);

  List<StockMoveEntity> findByAllocationDemandIdOrderByAllocationDemandLineIdAsc(
      UUID allocationDemandId);
}
