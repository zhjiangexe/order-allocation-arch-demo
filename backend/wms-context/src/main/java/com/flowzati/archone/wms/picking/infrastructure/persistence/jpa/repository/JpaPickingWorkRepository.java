package com.flowzati.archone.wms.picking.infrastructure.persistence.jpa.repository;

import com.flowzati.archone.wms.picking.infrastructure.persistence.jpa.model.PickingWorkEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JpaPickingWorkRepository extends JpaRepository<PickingWorkEntity, UUID> {

    Optional<PickingWorkEntity> findByShipmentId(UUID shipmentId);

    @Query("select distinct work from PickingWorkEntity work join work.pickTasks task where task.id = :pickTaskId")
    Optional<PickingWorkEntity> findByPickTaskId(@Param("pickTaskId") UUID pickTaskId);
}
