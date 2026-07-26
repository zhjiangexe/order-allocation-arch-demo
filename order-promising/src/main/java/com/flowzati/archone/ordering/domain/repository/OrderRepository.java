package com.flowzati.archone.ordering.domain.repository;

import com.flowzati.archone.ordering.domain.model.Order;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderRepository {
  void save(Order placedOrder);

  Optional<Order> findById(UUID orderId);

  List<Order> findBackordersBySkuInFifoOrder(String sku);

  /**
   * 依下單時間遞減取最近 {@code limit} 筆。以 id 遞減作為 tie-breaker——同一毫秒寫入的
   * 多筆訂單若沒有穩定的次序，重複查詢會回傳不同順序，畫面上的列表就會無故跳動。
   */
  List<Order> findRecent(int limit);
}
