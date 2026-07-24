package com.flowzati.archone.allocation.domain.repository;

import com.flowzati.archone.allocation.domain.model.StockPool;

import java.util.Optional;
import java.util.UUID;

public interface StockPoolRepository {
  Optional<StockPool> findById(UUID id);

  Optional<StockPool> findBySku(String sku);

  int save(StockPool stockPool);
}
