package com.flowzati.archone.inventory.reservation.infrastructure.persistence.jpa.repository;

import com.flowzati.archone.inventory.reservation.infrastructure.persistence.jpa.entity.OrderAllocationSourceLineEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaOrderAllocationSourceRepository extends JpaRepository<OrderAllocationSourceLineEntity, UUID> {

    List<OrderAllocationSourceLineEntity> findBySourceIdOrderBySourceLineIdAsc(UUID sourceId);
}
