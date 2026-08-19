package com.flowzati.archone.stock.movement.infrastructure.repository;

import com.flowzati.archone.stock.movement.domain.aggregate.StockPicking;
import com.flowzati.archone.stock.movement.domain.repository.StockPickingRepository;
import com.flowzati.archone.stock.movement.infrastructure.mapper.StockPickingMapper;
import com.flowzati.archone.stock.movement.infrastructure.repository.jpa.JpaStockPickingRepository;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class StockPickingRepositoryImpl implements StockPickingRepository {

  private final JpaStockPickingRepository repository;

  public StockPickingRepositoryImpl(JpaStockPickingRepository repository) {
    this.repository = repository;
  }

  @Override
  public void save(StockPicking picking) {
    repository.save(StockPickingMapper.toEntity(picking));
  }

  @Override
  public List<StockPicking> findByOrderId(UUID orderId) {
    return repository.findByOrderId(orderId).stream()
        .map(StockPickingMapper::toDomain)
        .toList();
  }

  @Override
  public List<StockPicking> findByIds(Collection<UUID> pickingIds) {
    if (pickingIds.isEmpty()) {
      return List.of();
    }
    return repository.findByIdIn(pickingIds).stream()
        .map(StockPickingMapper::toDomain)
        .toList();
  }
}
