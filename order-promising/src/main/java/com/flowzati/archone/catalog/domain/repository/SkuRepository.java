package com.flowzati.archone.catalog.domain.repository;

import com.flowzati.archone.catalog.domain.aggregate.Sku;
import java.util.List;
import java.util.UUID;

public interface SkuRepository {

  void save(Sku sku);

  /** 列出某貨主某一款的所有規格，以規格編碼遞增排序。 */
  List<Sku> findByProduct(UUID ownerId, String productCode);
}
