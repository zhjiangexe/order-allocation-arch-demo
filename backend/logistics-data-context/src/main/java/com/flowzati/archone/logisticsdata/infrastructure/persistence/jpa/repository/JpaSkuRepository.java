package com.flowzati.archone.logisticsdata.infrastructure.persistence.jpa.repository;

import com.flowzati.archone.logisticsdata.infrastructure.persistence.jpa.model.SkuEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaSkuRepository extends JpaRepository<SkuEntity, UUID> {

    List<SkuEntity> findByOwnerIdAndProductCodeOrderBySkuCodeAsc(UUID ownerId, String productCode);
}
