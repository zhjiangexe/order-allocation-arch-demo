package com.flowzati.archone.inventory.movement.infrastructure.repo;

import com.flowzati.archone.inventory.movement.application.repo.StockOperationTypeStore;
import com.flowzati.archone.inventory.movement.domain.StockOperationDirection;
import com.flowzati.archone.inventory.movement.domain.StockOperationType;
import com.flowzati.archone.inventory.movement.infrastructure.mapper.StockOperationTypeMapper;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class StockOperationTypePersistenceAdapter implements StockOperationTypeStore {

    private final JpaStockOperationTypeRepository jpaStockOperationTypeRepository;

    public StockOperationTypePersistenceAdapter(JpaStockOperationTypeRepository jpaStockOperationTypeRepository) {
        this.jpaStockOperationTypeRepository = jpaStockOperationTypeRepository;
    }

    @Override
    public void save(StockOperationType stockOperationType) {
        jpaStockOperationTypeRepository.save(StockOperationTypeMapper.toEntity(stockOperationType));
    }

    @Override
    public Optional<StockOperationType> findById(UUID stockOperationTypeId) {
        if (stockOperationTypeId == null) {
            return Optional.empty();
        }
        return jpaStockOperationTypeRepository.findById(stockOperationTypeId).map(StockOperationTypeMapper::toDomain);
    }

    @Override
    public Optional<StockOperationType> find(UUID facilityId, StockOperationDirection code) {
        if (facilityId == null || code == null) {
            return Optional.empty();
        }
        return jpaStockOperationTypeRepository
                .findByFacilityIdAndCode(facilityId, code)
                .map(StockOperationTypeMapper::toDomain);
    }
}
