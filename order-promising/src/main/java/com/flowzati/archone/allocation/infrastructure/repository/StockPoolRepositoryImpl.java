package com.flowzati.archone.allocation.infrastructure.repository;

import com.flowzati.archone.allocation.domain.model.StockPool;
import com.flowzati.archone.allocation.domain.repository.StockPoolRepository;
import com.flowzati.archone.allocation.infrastructure.mapper.StockPoolMapper;
import com.flowzati.archone.allocation.infrastructure.repository.jpa.JpaStockRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class StockPoolRepositoryImpl implements StockPoolRepository {

  private final JpaStockRepository repository;

  public StockPoolRepositoryImpl(JpaStockRepository repository) {
    this.repository = repository;
  }

  @Override
  public Optional<StockPool> findById(UUID id) {
    return repository.findById(id).map(StockPoolMapper::toDomain);
  }

  @Override
  public Optional<StockPool> findBySku(String sku) {
    return repository.findBySku(sku).map(StockPoolMapper::toDomain);
  }

  @Override
  public int save(StockPool stockPool) {
    repository.save(StockPoolMapper.toEntity(stockPool));
    return 1;
  }
}
