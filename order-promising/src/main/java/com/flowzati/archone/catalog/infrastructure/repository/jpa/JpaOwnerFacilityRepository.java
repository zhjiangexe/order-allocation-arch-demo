package com.flowzati.archone.catalog.infrastructure.repository.jpa;

import com.flowzati.archone.catalog.infrastructure.entity.OwnerFacilityEntity;
import com.flowzati.archone.catalog.infrastructure.entity.OwnerFacilityEntity.OwnerFacilityId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaOwnerFacilityRepository extends JpaRepository<OwnerFacilityEntity, OwnerFacilityId> {
}
