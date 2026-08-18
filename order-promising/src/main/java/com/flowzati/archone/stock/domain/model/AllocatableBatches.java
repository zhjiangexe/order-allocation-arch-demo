package com.flowzati.archone.stock.domain.model;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 同一貨主、同一庫位下，依 SKU 分組且各組已按配貨順序排列的可配批。
 *
 * <p>這不只是 {@code Map} 的別名。它保留三個配貨輸入不變式：批次不得跨貨主或庫位、批次必須
 * 掛在正確的 SKU 群組下，以及「沒有批」必須以空群組表示而不能省略該 SKU。最後一點讓正常缺貨
 * 與呼叫端漏載資料保持可區分。
 */
public final class AllocatableBatches {

  private final UUID ownerId;
  private final UUID locationId;
  private final Map<String, List<StockPool>> batchesBySku;

  private AllocatableBatches(
      UUID ownerId,
      UUID locationId,
      Map<String, ? extends Collection<StockPool>> batchesBySku) {
    this.ownerId = Objects.requireNonNull(ownerId, "ownerId must not be null");
    this.locationId = Objects.requireNonNull(locationId, "locationId must not be null");
    Objects.requireNonNull(batchesBySku, "batchesBySku must not be null");

    Map<String, List<StockPool>> copied = new LinkedHashMap<>();
    batchesBySku.forEach((skuCode, batches) -> {
      if (skuCode == null || skuCode.isBlank()) {
        throw new IllegalArgumentException("SKU code must not be blank");
      }
      Objects.requireNonNull(batches, "Batches for " + skuCode + " must not be null");
      List<StockPool> group = List.copyOf(batches);
      group.forEach(batch -> requireBatchInScope(skuCode, batch));
      copied.put(skuCode, group);
    });
    this.batchesBySku = Collections.unmodifiableMap(copied);
  }

  public static AllocatableBatches of(
      UUID ownerId,
      UUID locationId,
      Map<String, ? extends Collection<StockPool>> batchesBySku) {
    return new AllocatableBatches(ownerId, locationId, batchesBySku);
  }

  public UUID ownerId() {
    return ownerId;
  }

  public UUID locationId() {
    return locationId;
  }

  public Set<String> skuCodes() {
    return batchesBySku.keySet();
  }

  /** 回傳指定 SKU 的批；缺少群組是輸入錯誤，與存在但內容為空的缺貨不同。 */
  public List<StockPool> forSku(String skuCode) {
    List<StockPool> batches = batchesBySku.get(skuCode);
    if (batches == null) {
      throw new IllegalArgumentException("Batches are missing a group for SKU " + skuCode);
    }
    return batches;
  }

  public int availableToPromiseFor(String skuCode) {
    return forSku(skuCode).stream().mapToInt(StockPool::availableToPromise).sum();
  }

  public boolean isEmpty() {
    return batchesBySku.values().stream().allMatch(List::isEmpty);
  }

  /** Source-agnostic demand boundary used by the pure allocation planner. */
  public void requireCovers(AllocationDemand demand) {
    Objects.requireNonNull(demand, "demand must not be null");
    if (!ownerId.equals(demand.ownerId())) {
      throw new IllegalArgumentException("Demand and batches must belong to the same owner");
    }
    if (!locationId.equals(demand.locationId())) {
      throw new IllegalArgumentException("Demand and batches must belong to the same location");
    }
    if (!batchesBySku.keySet().containsAll(demand.totalsBySku().keySet())) {
      Set<String> missing = new LinkedHashSet<>(demand.totalsBySku().keySet());
      missing.removeAll(batchesBySku.keySet());
      throw new IllegalArgumentException("Batches are missing a group for demanded SKUs " + missing);
    }
  }

  private void requireBatchInScope(String skuCode, StockPool batch) {
    Objects.requireNonNull(batch, "Batch grouped under " + skuCode + " must not be null");
    if (!batch.getSkuCode().equals(skuCode)) {
      throw new IllegalArgumentException("Batch grouped under " + skuCode + " belongs to " + batch.getSkuCode());
    }
    if (!batch.getOwnerId().equals(ownerId)) {
      throw new IllegalArgumentException("Demand and batches must belong to the same owner");
    }
    if (!batch.getLocationId().equals(locationId)) {
      throw new IllegalArgumentException("Demand and batches must belong to the same location");
    }
  }
}
