package com.flowzati.archone.catalog.infrastructure.repository.jpa;

import com.flowzati.archone.catalog.infrastructure.entity.ProductEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaProductRepository extends JpaRepository<ProductEntity, UUID> {

  List<ProductEntity> findByOwnerIdOrderByProductCodeAsc(UUID ownerId);
}
