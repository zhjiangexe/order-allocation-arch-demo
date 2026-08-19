package com.flowzati.archone.catalog.infrastructure.repository.jpa;

import com.flowzati.archone.catalog.domain.type.LocationUsage;
import com.flowzati.archone.catalog.infrastructure.entity.StockLocationEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaStockLocationRepository extends JpaRepository<StockLocationEntity, UUID> {

    List<StockLocationEntity> findAllByFacilityIdAndUsageOrderByCode(UUID facilityId, LocationUsage usage);
}
