package com.flowzati.archone.logisticsdata.infrastructure.repository.jpa;

import com.flowzati.archone.logisticsdata.infrastructure.entity.SkuEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaSkuRepository extends JpaRepository<SkuEntity, UUID> {

    List<SkuEntity> findByOwnerIdAndProductCodeOrderBySkuCodeAsc(UUID ownerId, String productCode);
}
