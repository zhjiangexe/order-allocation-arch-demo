package com.flowzati.archone.wms.outbound.infrastructure.persistence.repository;

import com.flowzati.archone.wms.outbound.domain.type.ShipmentStatus;
import com.flowzati.archone.wms.outbound.infrastructure.persistence.entity.WmsShipmentEntity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JpaWmsShipmentRepository extends JpaRepository<WmsShipmentEntity, UUID> {

    Optional<WmsShipmentEntity> findByStockOperationId(UUID stockOperationId);

    List<WmsShipmentEntity> findByOrderIdOrderByCreatedAtAscIdAsc(UUID orderId);

    List<WmsShipmentEntity> findByFacilityIdAndStatus(UUID facilityId, ShipmentStatus status, Pageable pageable);

    @Query("select shipment.id from WmsShipmentEntity shipment "
            + "where shipment.status = :status and shipment.createdAt <= :cutoff "
            + "order by shipment.createdAt asc, shipment.id asc")
    List<UUID> findIdsCreatedAtOrBefore(
            @Param("status") ShipmentStatus status, @Param("cutoff") Instant cutoff, Pageable pageable);

    @Query("select shipment.id from WmsShipmentEntity shipment "
            + "where shipment.status = :status "
            + "order by shipment.cancellationRequestedAt asc, shipment.id asc")
    List<UUID> findIdsByStatus(@Param("status") ShipmentStatus status, Pageable pageable);

    @Query("select distinct shipment from WmsShipmentEntity shipment "
            + "join shipment.pickTasks task where task.id = :pickTaskId")
    Optional<WmsShipmentEntity> findByPickTaskId(@Param("pickTaskId") UUID pickTaskId);
}
