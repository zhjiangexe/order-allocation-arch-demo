package com.flowzati.archone.stock.domain.repository;

import com.flowzati.archone.stock.domain.model.Demand;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * allocation 取得待配需求的唯一入口，**唯讀**。
 *
 * <p>沒有任何寫入方法，這是刻意的：`orders` 與 `order_lines` 只由 ordering 寫。allocation
 * 記錄結果的方式是寫自己的表並發事件。
 *
 * <p>實作查的是 {@code demand_lines} view，因此 allocation 的程式碼不出現 {@code orders} 或
 * {@code order_lines} 這兩個表名——架構測試的「不得出現該表名」不需要為讀取開例外。
 */
public interface DemandRepository {

  /**
   * 某一張單還欠的東西。全部配到（或訂單已取消）時回 empty。
   *
   * <p>下單即配與取消釋放兩條路徑用它——它們處理的是指定的一張單，不需要排序也不需要上限。
   */
  Optional<Demand> findByOrderId(UUID orderId);
}
