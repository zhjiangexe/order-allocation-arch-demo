package com.flowzati.archone.wms.inbound.infrastructure.persistence.repository;

import com.flowzati.archone.wms.inbound.infrastructure.persistence.entity.WmsInboundOperationEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaWmsInboundOperationRepository extends JpaRepository<WmsInboundOperationEntity, UUID> {

    Optional<WmsInboundOperationEntity> findByExternalReference(String externalReference);
}
