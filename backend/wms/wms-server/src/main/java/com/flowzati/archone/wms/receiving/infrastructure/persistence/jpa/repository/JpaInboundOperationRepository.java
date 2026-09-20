package com.flowzati.archone.wms.receiving.infrastructure.persistence.jpa.repository;

import com.flowzati.archone.wms.receiving.infrastructure.persistence.jpa.model.InboundOperationEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaInboundOperationRepository extends JpaRepository<InboundOperationEntity, UUID> {

    Optional<InboundOperationEntity> findByExternalReference(String externalReference);
}
