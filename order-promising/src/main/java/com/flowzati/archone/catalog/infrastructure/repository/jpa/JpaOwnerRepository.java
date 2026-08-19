package com.flowzati.archone.catalog.infrastructure.repository.jpa;

import com.flowzati.archone.catalog.infrastructure.entity.OwnerEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaOwnerRepository extends JpaRepository<OwnerEntity, UUID> {

    List<OwnerEntity> findAllByOrderByCodeAsc();
}
