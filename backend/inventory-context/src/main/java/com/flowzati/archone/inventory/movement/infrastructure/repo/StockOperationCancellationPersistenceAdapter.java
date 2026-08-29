package com.flowzati.archone.inventory.movement.infrastructure.repo;

import com.flowzati.archone.inventory.movement.application.repo.StockOperationCancellationStore;
import com.flowzati.archone.inventory.movement.domain.StockOperationCancellation;
import com.flowzati.archone.inventory.movement.infrastructure.StockOperationCancellationKey;
import com.flowzati.archone.inventory.movement.infrastructure.mapper.StockOperationCancellationMapper;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class StockOperationCancellationPersistenceAdapter implements StockOperationCancellationStore {

    private final JpaStockOperationCancellationRepository jpaStockOperationCancellationRepository;

    public StockOperationCancellationPersistenceAdapter(
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
