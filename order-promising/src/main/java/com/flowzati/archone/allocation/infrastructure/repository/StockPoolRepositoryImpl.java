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
      UUID ownerId, UUID locationId, String skuCode, LocalDate today) {
    return repository
        .findAllocatableBatchesInFefoOrder(ownerId, locationId, skuCode, today)
        .stream()
        .map(StockPoolMapper::toDomain)
        .toList();
  }

  @Override
  public java.util.Map<String, List<StockPool>> findAllocatableBatchesBySku(
      UUID ownerId, UUID locationId, java.util.Collection<String> skuCodes, LocalDate today) {
    if (skuCodes.isEmpty()) {
      return java.util.Map.of();
    }

    // 每一個被問到的 SKU 都要有一筆，即使一批都沒有——空清單是缺貨，缺鍵是輸入錯誤。
    java.util.Map<String, List<StockPool>> grouped = new java.util.LinkedHashMap<>();
    skuCodes.forEach(skuCode -> grouped.put(skuCode, new java.util.ArrayList<>()));

    repository.findAllocatableBatchesInFefoOrder(ownerId, locationId, skuCodes, today).stream()
        .map(StockPoolMapper::toDomain)
        .forEach(batch -> grouped.get(batch.getSkuCode()).add(batch));

    grouped.replaceAll((skuCode, batches) -> List.copyOf(batches));
    return java.util.Map.copyOf(grouped);
  }

  @Override
  public java.util.Map<String, List<StockPool>> findBatchesInLocation(UUID ownerId, UUID locationId) {
    // LinkedHashMap 而不是 groupingBy 的預設 HashMap：查詢已經把同一個 SKU 的批排在一起且
    // 組內是 FEFO，用會重排鍵的 map 收就把那個順序丟掉一半。
    //
    // 同理，回傳**不能**包成 Map.copyOf——它的迭代順序未定義，一路排好的鍵在最後一步就散了。
    java.util.Map<String, List<StockPool>> bySku = new java.util.LinkedHashMap<>();
    repository.findByOwnerIdAndLocationIdOrderBySkuCodeAscExpiryDateAscInDateAscIdAsc(ownerId, locationId)
        .stream()
        .map(StockPoolMapper::toDomain)
        .forEach(batch -> bySku
            .computeIfAbsent(batch.getSkuCode(), key -> new java.util.ArrayList<>())
            .add(batch));

    bySku.replaceAll((skuCode, batches) -> List.copyOf(batches));
    return java.util.Collections.unmodifiableMap(bySku);
  }

  @Override
  public Optional<StockPool> findByIdentity(
      UUID ownerId, UUID locationId, String skuCode, LocalDate inDate, LocalDate expiryDate) {
    return repository
        .findByOwnerIdAndLocationIdAndSkuCodeAndInDateAndExpiryDate(
            ownerId, locationId, skuCode, inDate, expiryDate)
        .map(StockPoolMapper::toDomain);
  }

  @Override
  public int save(StockPool stockPool) {
    repository.save(StockPoolMapper.toEntity(stockPool));
    return 1;
  }
}
