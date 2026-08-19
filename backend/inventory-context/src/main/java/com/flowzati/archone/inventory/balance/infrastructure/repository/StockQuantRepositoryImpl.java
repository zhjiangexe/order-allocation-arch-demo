package com.flowzati.archone.inventory.balance.infrastructure.repository;

import com.flowzati.archone.inventory.balance.domain.aggregate.StockQuant;
import com.flowzati.archone.inventory.balance.domain.repository.StockQuantRepository;
import com.flowzati.archone.inventory.balance.domain.valueobject.AllocatableBatches;
import com.flowzati.archone.inventory.balance.infrastructure.mapper.StockQuantMapper;
import com.flowzati.archone.inventory.balance.infrastructure.repository.jpa.JpaStockQuantRepository;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class StockQuantRepositoryImpl implements StockQuantRepository {

    private final JpaStockQuantRepository repository;

    public StockQuantRepositoryImpl(JpaStockQuantRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<StockQuant> findById(UUID id) {
        return repository.findById(id).map(StockQuantMapper::toDomain);
    }

    @Override
    public List<StockQuant> findByIds(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return repository.findAllById(ids).stream()
                .map(StockQuantMapper::toDomain)
                .toList();
    }

    @Override
    public List<StockQuant> findAllocatableBatchesInFefoOrder(
            UUID ownerId, UUID locationId, String skuCode, LocalDate today) {
        return repository.findAllocatableBatchesInFefoOrder(ownerId, locationId, skuCode, today).stream()
                .map(StockQuantMapper::toDomain)
                .toList();
    }

    @Override
    public AllocatableBatches findAllocatableBatchesBySku(
            UUID ownerId, UUID locationId, java.util.Collection<String> skuCodes, LocalDate today) {
        if (skuCodes.isEmpty()) {
            return AllocatableBatches.of(ownerId, locationId, java.util.Map.of());
        }

        // 每一個被問到的 SKU 都要有一筆，即使一批都沒有——空清單是缺貨，缺鍵是輸入錯誤。
        java.util.Map<String, List<StockQuant>> grouped = new java.util.LinkedHashMap<>();
        skuCodes.forEach(skuCode -> grouped.put(skuCode, new java.util.ArrayList<>()));

        repository.findAllocatableBatchesInFefoOrder(ownerId, locationId, skuCodes, today).stream()
                .map(StockQuantMapper::toDomain)
                .forEach(batch -> grouped.get(batch.getSkuCode()).add(batch));

        return AllocatableBatches.of(ownerId, locationId, grouped);
    }

    @Override
    public java.util.Map<String, List<StockQuant>> findBatchesInLocation(UUID ownerId, UUID locationId) {
        // LinkedHashMap 而不是 groupingBy 的預設 HashMap：查詢已經把同一個 SKU 的批排在一起且
        // 組內是 FEFO，用會重排鍵的 map 收就把那個順序丟掉一半。
        //
        // 同理，回傳**不能**包成 Map.copyOf——它的迭代順序未定義，一路排好的鍵在最後一步就散了。
        java.util.Map<String, List<StockQuant>> bySku = new java.util.LinkedHashMap<>();
        repository.findByOwnerIdAndLocationIdOrderBySkuCodeAscExpiryDateAscInDateAscIdAsc(ownerId, locationId).stream()
                .map(StockQuantMapper::toDomain)
                .forEach(batch -> bySku.computeIfAbsent(batch.getSkuCode(), key -> new java.util.ArrayList<>())
                        .add(batch));

        bySku.replaceAll((skuCode, batches) -> List.copyOf(batches));
        return java.util.Collections.unmodifiableMap(bySku);
    }

    @Override
    public Optional<StockQuant> findByIdentity(
            UUID ownerId, UUID locationId, String skuCode, LocalDate inDate, LocalDate expiryDate) {
        return repository
                .findByOwnerIdAndLocationIdAndSkuCodeAndInDateAndExpiryDate(
                        ownerId, locationId, skuCode, inDate, expiryDate)
                .map(StockQuantMapper::toDomain);
    }

    @Override
    public int save(StockQuant stockQuant) {
        repository.save(StockQuantMapper.toEntity(stockQuant));
        return 1;
    }
}
