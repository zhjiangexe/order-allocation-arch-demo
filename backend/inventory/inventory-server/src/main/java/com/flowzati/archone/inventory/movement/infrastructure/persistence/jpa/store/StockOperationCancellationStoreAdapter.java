package com.flowzati.archone.inventory.movement.infrastructure.persistence.jpa.store;

import com.flowzati.archone.inventory.movement.application.store.StockOperationCancellationStore;
import com.flowzati.archone.inventory.movement.domain.aggregate.StockOperationCancellation;
import com.flowzati.archone.inventory.movement.infrastructure.persistence.jpa.entity.StockOperationCancellationKey;
import com.flowzati.archone.inventory.movement.infrastructure.persistence.jpa.mapper.StockOperationCancellationMapper;
import com.flowzati.archone.inventory.movement.infrastructure.persistence.jpa.repository.JpaStockOperationCancellationRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class StockOperationCancellationStoreAdapter implements StockOperationCancellationStore {

    private final JpaStockOperationCancellationRepository jpaStockOperationCancellationRepository;

    public StockOperationCancellationStoreAdapter(
            JpaStockOperationCancellationRepository jpaStockOperationCancellationRepository) {
        this.jpaStockOperationCancellationRepository = jpaStockOperationCancellationRepository;
    }

    @Override
    public StockOperationCancellation save(StockOperationCancellation operation) {
        return StockOperationCancellationMapper.toDomain(
                jpaStockOperationCancellationRepository.save(StockOperationCancellationMapper.toEntity(operation)));
    }

    @Override
    public Optional<StockOperationCancellation> find(UUID stockOperationId, UUID cancellationOperationId) {
        return jpaStockOperationCancellationRepository
                .findById(new StockOperationCancellationKey(stockOperationId, cancellationOperationId))
                .map(StockOperationCancellationMapper::toDomain);
    }
}
