package com.flowzati.archone.allocation.infrastructure.repository.jpa;

import com.flowzati.archone.allocation.infrastructure.entity.StockPoolEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface JpaStockRepository extends JpaRepository<StockPoolEntity, UUID> {
  Optional<StockPoolEntity> findBySku(String sku);
}
