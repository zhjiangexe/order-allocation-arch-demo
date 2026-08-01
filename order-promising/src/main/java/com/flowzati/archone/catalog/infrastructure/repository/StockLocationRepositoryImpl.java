package com.flowzati.archone.catalog.infrastructure.repository;

import com.flowzati.archone.catalog.domain.model.LocationUsage;
import com.flowzati.archone.catalog.domain.model.StockLocation;
import com.flowzati.archone.catalog.domain.repository.StockLocationRepository;
import com.flowzati.archone.catalog.infrastructure.mapper.StockLocationMapper;
import com.flowzati.archone.catalog.infrastructure.repository.jpa.JpaStockLocationRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class StockLocationRepositoryImpl implements StockLocationRepository {

  private final JpaStockLocationRepository repository;

  public StockLocationRepositoryImpl(JpaStockLocationRepository repository) {
    this.repository = repository;
  }

  @Override
  public void save(StockLocation location) {
    repository.save(StockLocationMapper.toEntity(location));
  }

  @Override
  public Optional<StockLocation> findInternalOf(UUID warehouseId) {
    if (warehouseId == null) {
      return Optional.empty();
    }
    return repository
        .findByWarehouseIdAndUsage(warehouseId, LocationUsage.INTERNAL)
        .map(StockLocationMapper::toDomain);
  }
}
