package com.flowzati.archone.inventory.warehouse.infrastructure.repository.jpa;

import com.flowzati.archone.inventory.warehouse.domain.type.LocationUsageType;
import com.flowzati.archone.inventory.warehouse.infrastructure.entity.StockLocationEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaStockLocationRepository extends JpaRepository<StockLocationEntity, UUID> {

    List<StockLocationEntity> findAllByFacilityIdAndUsageOrderByCode(UUID facilityId, LocationUsageType usage);
}
