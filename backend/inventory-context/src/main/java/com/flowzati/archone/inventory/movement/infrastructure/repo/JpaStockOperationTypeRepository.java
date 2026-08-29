package com.flowzati.archone.inventory.movement.infrastructure.repo;

import com.flowzati.archone.inventory.movement.domain.StockOperationDirection;
import com.flowzati.archone.inventory.movement.infrastructure.entity.StockOperationTypeEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaStockOperationTypeRepository extends JpaRepository<StockOperationTypeEntity, UUID> {

    Optional<StockOperationTypeEntity> findByFacilityIdAndCode(UUID facilityId, StockOperationDirection code);
}
