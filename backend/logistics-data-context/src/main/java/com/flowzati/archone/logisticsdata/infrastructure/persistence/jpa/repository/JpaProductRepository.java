package com.flowzati.archone.logisticsdata.infrastructure.persistence.jpa.repository;

import com.flowzati.archone.logisticsdata.infrastructure.persistence.jpa.model.ProductEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaProductRepository extends JpaRepository<ProductEntity, UUID> {

    List<ProductEntity> findByOwnerIdOrderByProductCodeAsc(UUID ownerId);
}
