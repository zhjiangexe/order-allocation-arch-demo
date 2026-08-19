package com.flowzati.archone.inventory.warehouse.infrastructure.repository.jpa;

import com.flowzati.archone.inventory.warehouse.domain.type.PickingDirection;
import com.flowzati.archone.inventory.warehouse.infrastructure.entity.PickingTypeEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaPickingTypeRepository extends JpaRepository<PickingTypeEntity, UUID> {

    Optional<PickingTypeEntity> findByFacilityIdAndCode(UUID facilityId, PickingDirection code);
}
