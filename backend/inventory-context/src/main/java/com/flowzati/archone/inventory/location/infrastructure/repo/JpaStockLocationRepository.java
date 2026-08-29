package com.flowzati.archone.inventory.location.infrastructure.repo;

import com.flowzati.archone.inventory.location.domain.LocationUsageType;
import com.flowzati.archone.inventory.location.infrastructure.entity.StockLocationEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaStockLocationRepository extends JpaRepository<StockLocationEntity, UUID> {

    List<StockLocationEntity> findAllByFacilityIdAndUsageOrderByCode(UUID facilityId, LocationUsageType usage);
}
