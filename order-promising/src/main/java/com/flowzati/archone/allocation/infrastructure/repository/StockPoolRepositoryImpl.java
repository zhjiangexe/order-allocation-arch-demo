package com.flowzati.archone.allocation.infrastructure.repository;

import com.flowzati.archone.allocation.domain.model.StockPool;
import com.flowzati.archone.allocation.domain.repository.StockPoolRepository;
import com.flowzati.archone.allocation.infrastructure.mapper.StockPoolMapper;
import com.flowzati.archone.allocation.infrastructure.repository.jpa.JpaStockRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

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
  public List<StockPool> findAllocatableBatchesInFefoOrder(
      UUID ownerId, UUID nodeId, String skuCode, LocalDate today) {
    return repository
        .findAllocatableBatchesInFefoOrder(ownerId, nodeId, skuCode, today)
        .stream()
        .map(StockPoolMapper::toDomain)
        .toList();
  }

  @Override
  public List<StockPool> findBatchesAcrossNodes(UUID ownerId, String skuCode) {
    return repository
        .findByOwnerIdAndSkuCodeOrderByNodeIdAscExpiryDateAscInDateAscIdAsc(ownerId, skuCode)
        .stream()
        .map(StockPoolMapper::toDomain)
        .toList();
  }

  @Override
  public Optional<StockPool> findByIdentity(
      UUID ownerId, UUID nodeId, String skuCode, LocalDate inDate, LocalDate expiryDate) {
    return repository
        .findByOwnerIdAndNodeIdAndSkuCodeAndInDateAndExpiryDate(
            ownerId, nodeId, skuCode, inDate, expiryDate)
        .map(StockPoolMapper::toDomain);
  }

  @Override
  public int save(StockPool stockPool) {
    repository.save(StockPoolMapper.toEntity(stockPool));
    return 1;
  }
}
