package com.flowzati.archone.wms.dispatch.infrastructure.persistence.jpa.repository;

import com.flowzati.archone.wms.dispatch.infrastructure.persistence.jpa.model.ShipmentDispatchEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaShipmentDispatchRepository extends JpaRepository<ShipmentDispatchEntity, UUID> {

    Optional<ShipmentDispatchEntity> findByShipmentId(UUID shipmentId);
}
