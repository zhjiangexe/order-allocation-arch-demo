package com.flowzati.archone.inventory.movement.infrastructure.repo;

import com.flowzati.archone.inventory.movement.application.repo.StockOperationStore;
import com.flowzati.archone.inventory.movement.domain.StockOperationSource;
import com.flowzati.archone.inventory.movement.domain.aggregate.StockOperation;
import com.flowzati.archone.inventory.movement.infrastructure.mapper.StockOperationMapper;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class StockOperationPersistenceAdapter implements StockOperationStore {

    private final JpaStockOperationRepository jpaStockOperationRepository;

    public StockOperationPersistenceAdapter(JpaStockOperationRepository jpaStockOperationRepository) {
        this.jpaStockOperationRepository = jpaStockOperationRepository;
    }

    @Override
    public void save(StockOperation operation) {
        jpaStockOperationRepository.save(StockOperationMapper.toEntity(operation));
    }

    @Override
    public Optional<StockOperation> findById(UUID stockOperationId) {
        return jpaStockOperationRepository.findById(stockOperationId).map(StockOperationMapper::toDomain);
    }

    @Override
    public Optional<StockOperation> findBySource(StockOperationSource source) {
        return jpaStockOperationRepository
                .findBySourceTypeAndSourceIdAndAllocationUnitKey(
                        source.sourceType(), source.sourceId(), source.allocationUnitKey())
                .map(StockOperationMapper::toDomain);
    }

    @Override
    public Optional<StockOperation> lockById(UUID stockOperationId) {
        return jpaStockOperationRepository.findLockedById(stockOperationId).map(StockOperationMapper::toDomain);
    }

    @Override
    public List<StockOperation> findByIds(Collection<UUID> stockOperationIds) {
        if (stockOperationIds.isEmpty()) {
            return List.of();
        }
        return jpaStockOperationRepository.findByIdIn(stockOperationIds).stream()
                .map(StockOperationMapper::toDomain)
                .toList();
    }
}
