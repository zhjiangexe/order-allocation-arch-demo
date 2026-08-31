package com.flowzati.archone.logisticsdata.application.store;

import com.flowzati.archone.logisticsdata.domain.aggregate.Sku;
import java.util.List;
import java.util.UUID;

public interface SkuStore {

    void save(Sku sku);

    /** 列出某貨主某一款的所有規格，以規格編碼遞增排序。 */
    List<Sku> findByProduct(UUID ownerId, String productCode);
}
