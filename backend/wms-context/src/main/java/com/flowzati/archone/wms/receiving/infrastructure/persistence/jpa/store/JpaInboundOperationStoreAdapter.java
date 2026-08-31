package com.flowzati.archone.wms.receiving.infrastructure.persistence.jpa.store;

import com.flowzati.archone.wms.receiving.application.store.InboundOperationStore;
import com.flowzati.archone.wms.receiving.domain.aggregate.InboundOperation;
import com.flowzati.archone.wms.receiving.infrastructure.persistence.jpa.model.InboundOperationEntity;
import com.flowzati.archone.wms.receiving.infrastructure.persistence.jpa.repository.JpaInboundOperationRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class JpaInboundOperationStoreAdapter implements InboundOperationStore {

    private final JpaInboundOperationRepository repository;

    public JpaInboundOperationStoreAdapter(JpaInboundOperationRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<InboundOperation> findById(UUID inboundOperationId) {
        return repository.findById(inboundOperationId).map(InboundOperationEntity::toDomain);
    }

    @Override
    public Optional<InboundOperation> findByExternalReference(String externalReference) {
        return repository.findByExternalReference(externalReference).map(InboundOperationEntity::toDomain);
    }

    @Override
    public void save(InboundOperation operation) {
        InboundOperationEntity entity = repository
                .findById(operation.id())
                .map(existing -> {
                    existing.replaceFrom(operation);
                    return existing;
                })
                .orElseGet(() -> new InboundOperationEntity(operation));
        repository.save(entity);
    }
}
