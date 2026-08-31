package com.flowzati.archone.logisticsdata.infrastructure.persistence.jpa.repository;

import com.flowzati.archone.logisticsdata.infrastructure.persistence.jpa.model.OwnerEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaOwnerRepository extends JpaRepository<OwnerEntity, UUID> {

    List<OwnerEntity> findAllByOrderByCodeAsc();
}
