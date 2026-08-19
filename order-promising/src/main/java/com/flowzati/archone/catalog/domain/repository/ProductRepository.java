package com.flowzati.archone.catalog.domain.repository;

import com.flowzati.archone.catalog.domain.aggregate.Product;
import java.util.List;
import java.util.UUID;

public interface ProductRepository {

  void save(Product product);

  /**
   * 列出某貨主的所有款，以款號遞增排序。
   *
   * <p>沒有「列出所有款」的查詢：款號由貨主自訂且跨貨主撞號，不帶貨主的清單無法解讀。
   */
  List<Product> findByOwner(UUID ownerId);
}
