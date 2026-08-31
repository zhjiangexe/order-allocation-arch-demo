package com.flowzati.archone.logisticsdata.infrastructure.persistence.jpa.repository;

import com.flowzati.archone.logisticsdata.infrastructure.persistence.jpa.model.OwnerFacilityEntity;
import com.flowzati.archone.logisticsdata.infrastructure.persistence.jpa.model.OwnerFacilityEntity.OwnerFacilityId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaOwnerFacilityRepository extends JpaRepository<OwnerFacilityEntity, OwnerFacilityId> {}
