package com.flowzati.archone.logisticsdata.infrastructure.repository.jpa;

import com.flowzati.archone.logisticsdata.infrastructure.entity.OwnerFacilityEntity;
import com.flowzati.archone.logisticsdata.infrastructure.entity.OwnerFacilityEntity.OwnerFacilityId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaOwnerFacilityRepository extends JpaRepository<OwnerFacilityEntity, OwnerFacilityId> {}
