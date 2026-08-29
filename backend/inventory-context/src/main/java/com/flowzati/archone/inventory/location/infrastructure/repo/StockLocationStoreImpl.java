package com.flowzati.archone.inventory.location.infrastructure.repo;

import com.flowzati.archone.inventory.location.application.repo.StockLocationStore;
import com.flowzati.archone.inventory.location.domain.StockLocation;
import com.flowzati.archone.inventory.location.infrastructure.mapper.StockLocationMapper;
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
