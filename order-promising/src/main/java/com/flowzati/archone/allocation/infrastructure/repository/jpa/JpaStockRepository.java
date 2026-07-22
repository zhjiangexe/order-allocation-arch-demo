package com.flowzati.archone.allocation.infrastructure.repository.jpa;

import com.flowzati.archone.allocation.infrastructure.entity.StockPoolEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface JpaStockRepository extends JpaRepository<StockPoolEntity, Long> {
  Optional<StockPoolEntity> findBySku(String sku);
}
