package com.flowzati.archone.allocation.infrastructure.repository;

import com.flowzati.archone.allocation.domain.model.StockPool;
import com.flowzati.archone.allocation.domain.repository.StockPoolRepository;
import com.flowzati.archone.allocation.infrastructure.mapper.StockPoolMapper;
import com.flowzati.archone.allocation.infrastructure.repository.jpa.JpaStockRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class StockPoolRepositoryImpl implements StockPoolRepository {

  private final JpaStockRepository repository;

  public StockPoolRepositoryImpl(JpaStockRepository repository) {
    this.repository = repository;
  }

  @Override
  public Optional<StockPool> findById(Long id) {
    return repository.findById(id).map(StockPoolMapper::toDomain);
  }

  @Override
  public Optional<StockPool> findBySku(String sku) {
    // 這裡應該呼叫 jpa repository 根據 sku 查詢，目前先維持原樣或補齊
    return repository.findBySku(sku).map(StockPoolMapper::toDomain);
  }

  @Override
  public int save(StockPool stockPool) {
    // 將領域對象轉回 Entity 並儲存
    repository.save(StockPoolMapper.toEntity(stockPool));
    return 1;
  }
}
