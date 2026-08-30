package com.flowzati.archone.wms.inbound.infrastructure.persistence.adapter;

import com.flowzati.archone.wms.inbound.application.store.InboundOperationStore;
import com.flowzati.archone.wms.inbound.domain.aggregate.InboundOperation;
import com.flowzati.archone.wms.inbound.infrastructure.persistence.entity.WmsInboundOperationEntity;
import com.flowzati.archone.wms.inbound.infrastructure.persistence.repository.JpaWmsInboundOperationRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class JpaInboundOperationStoreImpl implements InboundOperationStore {

    private final JpaWmsInboundOperationRepository repository;

    public JpaInboundOperationStoreImpl(JpaWmsInboundOperationRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<InboundOperation> findById(UUID inboundOperationId) {
        return repository.findById(inboundOperationId).map(WmsInboundOperationEntity::toDomain);
    }

    @Override
    public Optional<InboundOperation> findByExternalReference(String externalReference) {
        return repository.findByExternalReference(externalReference).map(WmsInboundOperationEntity::toDomain);
    }

    @Override
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
