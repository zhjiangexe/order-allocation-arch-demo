package com.flowzati.archone.wms.inbound.infrastructure.persistence.repository;

import com.flowzati.archone.wms.inbound.domain.aggregate.InboundOperation;
import com.flowzati.archone.wms.inbound.domain.repository.InboundOperationRepository;
import com.flowzati.archone.wms.inbound.infrastructure.persistence.entity.WmsInboundOperationEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JpaInboundOperationRepositoryAdapter implements InboundOperationRepository {

    private final JpaWmsInboundOperationRepository repository;

    public JpaInboundOperationRepositoryAdapter(JpaWmsInboundOperationRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<InboundOperation> findById(UUID inboundOperationId) {
        return repository.findById(inboundOperationId).map(WmsInboundOperationEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<InboundOperation> findByExternalReference(String externalReference) {
        return repository.findByExternalReference(externalReference).map(WmsInboundOperationEntity::toDomain);
    }

    @Override
    @Transactional
    public void save(InboundOperation operation) {
        WmsInboundOperationEntity entity = repository
                .findById(operation.id())
                .map(existing -> {
                    existing.replaceFrom(operation);
                    return existing;
                })
                .orElseGet(() -> new WmsInboundOperationEntity(operation));
        repository.save(entity);
    }
}
