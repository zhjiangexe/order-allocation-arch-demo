package com.flowzati.archone.inventory.movement.infrastructure.persistence.jpa.store;

import com.flowzati.archone.inventory.movement.application.store.StockOperationTypeStore;
import com.flowzati.archone.inventory.movement.domain.entity.StockOperationType;
import com.flowzati.archone.inventory.movement.domain.valueobject.StockOperationDirection;
import com.flowzati.archone.inventory.movement.infrastructure.persistence.jpa.mapper.StockOperationTypeMapper;
import com.flowzati.archone.inventory.movement.infrastructure.persistence.jpa.repository.JpaStockOperationTypeRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class StockOperationTypeStoreImpl implements StockOperationTypeStore {

    private final JpaStockOperationTypeRepository jpaStockOperationTypeRepository;

    public StockOperationTypeStoreImpl(JpaStockOperationTypeRepository jpaStockOperationTypeRepository) {
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
