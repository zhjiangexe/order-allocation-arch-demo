package com.flowzati.archone.allocation.domain.repository;

import com.flowzati.archone.allocation.domain.model.Demand;
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
   * 某個 {@code (貨主, 倉, SKU)} 還欠貨的訂單，依到達順序，最多 {@code limit} 張。
   *
   * <p><b>回的是整張單，不是命中該 SKU 的那些行。</b>每個 {@link Demand} 帶著它所有還欠的行，
   * 包含別的 SKU 的——一張單整批配到或整批不配，決策需要看見整籃。這也讓喚醒上限的維度對齊：
   * 上限數的是張數，而這裡回的就是張。
   *
   * <p><b>倉別是範圍的一部分，不只是可用的參數。</b>庫存按貨主、倉庫、入庫日與效期持有，別的
   * 倉的單這次補貨滿足不了；把它們撈進來只會佔滿上限然後被跳過。
   *
   * <p>順序是訂單進入系統的順序（{@code order_id} 是 UUID v7）。不是進入缺貨的時間——那是系統
   * 的處理時刻，retry 與 rebalance 都會改變它；也不是上游的下單時刻——那可空，而且由我們控制
   * 不了的時鐘決定。
   *
   * @param limit 最多回幾張單，必須為正
   */
  List<Demand> findOutstandingDemandInFifoOrder(
      UUID ownerId, UUID locationId, String skuCode, int limit);

  /**
   * 某一張單還欠的東西。全部配到（或訂單已取消）時回 empty。
   *
   * <p>下單即配與取消釋放兩條路徑用它——它們處理的是指定的一張單，不需要排序也不需要上限。
   */
  Optional<Demand> findByOrderId(UUID orderId);
}
