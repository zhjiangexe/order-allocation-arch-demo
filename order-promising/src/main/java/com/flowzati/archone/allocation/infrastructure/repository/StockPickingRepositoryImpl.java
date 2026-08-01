package com.flowzati.archone.allocation.infrastructure.repository;

import com.flowzati.archone.allocation.domain.model.StockPicking;
import com.flowzati.archone.allocation.domain.repository.StockPickingRepository;
import com.flowzati.archone.allocation.infrastructure.entity.StockPickingEntity;
import com.flowzati.archone.allocation.infrastructure.mapper.StockPickingMapper;
import com.flowzati.archone.allocation.infrastructure.repository.jpa.JpaStockPickingRepository;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Repository;

@Repository
public class StockPickingRepositoryImpl implements StockPickingRepository {

  private final JpaStockPickingRepository repository;

  public StockPickingRepositoryImpl(JpaStockPickingRepository repository) {
    this.repository = repository;
  }

  @Override
  public void save(StockPicking picking, UUID orderId) {
    repository.save(StockPickingMapper.toEntity(picking, orderId));
  }

  @Override
  public List<StockPicking> findByOrderId(UUID orderId) {
    return repository.findByOrderId(orderId).stream()
        .map(StockPickingMapper::toDomain)
        .toList();
  }

  @Override
  public Map<UUID, UUID> findOrderIdsByIds(Collection<UUID> pickingIds) {
    if (pickingIds.isEmpty()) {
      return Map.of();
    }
    return repository.findByIdIn(pickingIds).stream()
        .filter(picking -> picking.getOrderId() != null)
        .collect(Collectors.toMap(
            StockPickingEntity::getId,
            StockPickingEntity::getOrderId,
            (a, b) -> a,
            LinkedHashMap::new));
  }
}
