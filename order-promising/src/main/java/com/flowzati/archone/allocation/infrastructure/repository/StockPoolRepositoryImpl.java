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
  public java.util.Map<String, List<StockPool>> findAllocatableBatchesBySku(
      UUID ownerId, UUID nodeId, java.util.Collection<String> skuCodes, LocalDate today) {
    if (skuCodes.isEmpty()) {
      return java.util.Map.of();
    }

    // 每一個被問到的 SKU 都要有一筆，即使一批都沒有——空清單是缺貨，缺鍵是輸入錯誤。
    java.util.Map<String, List<StockPool>> grouped = new java.util.LinkedHashMap<>();
    skuCodes.forEach(skuCode -> grouped.put(skuCode, new java.util.ArrayList<>()));

    repository.findAllocatableBatchesInFefoOrder(ownerId, nodeId, skuCodes, today).stream()
        .map(StockPoolMapper::toDomain)
        .forEach(batch -> grouped.get(batch.getSkuCode()).add(batch));

    grouped.replaceAll((skuCode, batches) -> List.copyOf(batches));
    return java.util.Map.copyOf(grouped);
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
