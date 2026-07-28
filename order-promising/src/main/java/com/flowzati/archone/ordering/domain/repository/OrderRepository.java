package com.flowzati.archone.ordering.domain.repository;

import com.flowzati.archone.ordering.domain.model.Order;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderRepository {
  void save(Order placedOrder);

  Optional<Order> findById(UUID orderId);

  /**
   * 某貨主某 SKU 的缺貨佇列，依進入缺貨的時間排序。
   *
   * <p>貨主是必要參數而非選用篩選：SKU 代碼跨貨主撞號，A 貨主的單不該被 B 貨主的單卡住。
   */
  List<Order> findBackordersBySkuInFifoOrder(UUID ownerId, String skuCode);

  /**
   * 依下單時間遞減取最近 {@code limit} 筆。以 id 遞減作為 tie-breaker——同一毫秒寫入的
   * 多筆訂單若沒有穩定的次序，重複查詢會回傳不同順序，畫面上的列表就會無故跳動。
   */
  List<Order> findRecent(int limit);
}
