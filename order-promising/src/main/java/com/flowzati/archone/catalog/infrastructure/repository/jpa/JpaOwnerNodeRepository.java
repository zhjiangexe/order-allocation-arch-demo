package com.flowzati.archone.catalog.infrastructure.repository.jpa;

import com.flowzati.archone.catalog.infrastructure.entity.OwnerNodeEntity;
import com.flowzati.archone.catalog.infrastructure.entity.OwnerNodeEntity.OwnerNodeId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaOwnerNodeRepository extends JpaRepository<OwnerNodeEntity, OwnerNodeId> {
}
