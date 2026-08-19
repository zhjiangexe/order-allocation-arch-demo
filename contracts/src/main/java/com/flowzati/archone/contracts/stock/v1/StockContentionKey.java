package com.flowzati.archone.contracts.stock.v1;

import java.util.UUID;

/**
 * 會競爭同一個貨主、同一座倉庫庫存的訊息共用 partition key。
 *
 * <p>key 固定使用 {@code (ownerId, facilityId)}，刻意不含 location 或 SKU。使用 location
 * 可能把仍會取得同一把庫存鎖的寫入拆到不同 writer；使用 SKU 則無法涵蓋 ship-complete
 * 的多 SKU 原子配置。以 Facility 分區可能過度序列化，但方向是「較慢而正確」，不會失去
 * single-writer 保證。
 *
 * <p>這是 producer 與 consumer 之間的排序契約；放在 contracts，避免 Ordering 與 Stock
 * 為了取得同一套演算法而直接依賴彼此的 application 或 infrastructure package。
 */
public final class StockContentionKey {

  private StockContentionKey() {
  }

  public static String of(UUID ownerId, UUID facilityId) {
    if (ownerId == null || facilityId == null) {
      throw new IllegalArgumentException("Owner ID and facility ID are required");
    }
    return ownerId + "/" + facilityId;
  }
}
