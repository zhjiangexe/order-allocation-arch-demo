package com.flowzati.archone.allocation.domain.repository;

import com.flowzati.archone.allocation.domain.model.StockPool;

import java.util.Optional;

public interface StockPoolRepository {
  Optional<StockPool> findById(Long id);

  Optional<StockPool> findBySku(String sku);

  int save(StockPool stockPool);
}
