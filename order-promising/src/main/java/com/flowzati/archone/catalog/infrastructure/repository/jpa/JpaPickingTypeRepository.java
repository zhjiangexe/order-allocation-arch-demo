package com.flowzati.archone.catalog.infrastructure.repository.jpa;

import com.flowzati.archone.catalog.domain.type.PickingDirection;
import com.flowzati.archone.catalog.infrastructure.entity.PickingTypeEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaPickingTypeRepository extends JpaRepository<PickingTypeEntity, UUID> {

    Optional<PickingTypeEntity> findByFacilityIdAndCode(UUID facilityId, PickingDirection code);
}
