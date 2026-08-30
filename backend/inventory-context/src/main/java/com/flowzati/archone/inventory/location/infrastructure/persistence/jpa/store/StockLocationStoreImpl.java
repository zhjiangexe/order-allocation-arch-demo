package com.flowzati.archone.inventory.location.infrastructure.persistence.jpa.store;

import com.flowzati.archone.inventory.location.application.store.StockLocationStore;
import com.flowzati.archone.inventory.location.domain.entity.StockLocation;
import com.flowzati.archone.inventory.location.infrastructure.persistence.jpa.mapper.StockLocationMapper;
import com.flowzati.archone.inventory.location.infrastructure.persistence.jpa.repository.JpaStockLocationRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class StockLocationStoreImpl implements StockLocationStore {

    private final JpaStockLocationRepository jpaStockLocationRepository;

    public StockLocationStoreImpl(JpaStockLocationRepository jpaStockLocationRepository) {
        this.jpaStockLocationRepository = jpaStockLocationRepository;
    }

    @Override
    public void save(StockLocation location) {
        jpaStockLocationRepository.save(StockLocationMapper.toEntity(location));
    }

    @Override
    public Optional<StockLocation> findById(UUID locationId) {
        if (locationId == null) {
            return Optional.empty();
        }
        return jpaStockLocationRepository.findById(locationId).map(StockLocationMapper::toDomain);
    }
}
