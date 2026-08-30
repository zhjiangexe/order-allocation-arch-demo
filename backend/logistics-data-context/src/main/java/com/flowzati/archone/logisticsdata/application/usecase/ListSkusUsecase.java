package com.flowzati.archone.logisticsdata.application.usecase;

import com.flowzati.archone.logisticsdata.application.store.SkuRepository;
import com.flowzati.archone.logisticsdata.domain.aggregate.Sku;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class ListSkusUsecase {

    private final SkuRepository skuRepository;

    public ListSkusUsecase(SkuRepository skuRepository) {
        this.skuRepository = skuRepository;
    }

    /** 款號只在其貨主之下有意義，因此兩個參數缺一不可。 */
    public List<Sku> listByProduct(UUID ownerId, String productCode) {
        return skuRepository.findByProduct(ownerId, productCode);
    }
}
