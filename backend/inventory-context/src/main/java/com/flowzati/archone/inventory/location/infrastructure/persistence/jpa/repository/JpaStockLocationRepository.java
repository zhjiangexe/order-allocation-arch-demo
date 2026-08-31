package com.flowzati.archone.inventory.location.infrastructure.persistence.jpa.repository;

import com.flowzati.archone.inventory.location.domain.valueobject.LocationUsageType;
import com.flowzati.archone.inventory.location.infrastructure.persistence.jpa.model.StockLocationEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaStockLocationRepository extends JpaRepository<StockLocationEntity, UUID> {

    List<StockLocationEntity> findAllByFacilityIdAndUsageOrderByCode(UUID facilityId, LocationUsageType usage);
}
