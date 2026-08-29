package com.flowzati.archone.inventory.reservation.infrastructure.repo.jpa;

import com.flowzati.archone.inventory.reservation.infrastructure.entity.OrderAllocationSourceLineEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaOrderAllocationSourceRepository extends JpaRepository<OrderAllocationSourceLineEntity, UUID> {

    List<OrderAllocationSourceLineEntity> findBySourceIdOrderBySourceLineIdAsc(UUID sourceId);
}
