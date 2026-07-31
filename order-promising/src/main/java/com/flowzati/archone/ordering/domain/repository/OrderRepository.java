package com.flowzati.archone.ordering.domain.repository;

import com.flowzati.archone.ordering.domain.model.Order;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderRepository {
  void save(Order placedOrder);

  Optional<Order> findById(UUID orderId);

  /**
   * 某貨主某 SKU 的缺貨佇列，依進入缺貨的時間排序，最多 {@code limit} 張。
   *
   * <p>貨主是必要參數而非選用篩選：SKU 代碼跨貨主撞號，A 貨主的單不該被 B 貨主的單卡住。
   *
   * <p><b>上限是必要參數，沒有無上限的版本。</b>一次補貨要改動幾張單，原本由佇列內容而不是
   * 由事件決定；分批之後涉及的批數也隨之不可預測，而防死鎖的寫入排序依賴「事先知道會碰哪些
   * 列」。留一個無上限的多載，等於留著那條路讓人不小心走回去。

  /**
   * 依下單時間遞減取最近 {@code limit} 筆。以 id 遞減作為 tie-breaker——同一毫秒寫入的
   * 多筆訂單若沒有穩定的次序，重複查詢會回傳不同順序，畫面上的列表就會無故跳動。
   */
  List<Order> findRecent(int limit);
}
