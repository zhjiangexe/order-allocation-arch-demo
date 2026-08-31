package com.flowzati.archone.logisticsdata.application.usecase;

import com.flowzati.archone.logisticsdata.application.store.ProductStore;
import com.flowzati.archone.logisticsdata.domain.aggregate.Product;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class ListProductsUsecase {

    private final ProductStore productStore;

    public ListProductsUsecase(ProductStore productStore) {
        this.productStore = productStore;
    }

    /** 貨主是必要參數而非選用篩選：款號跨貨主撞號，不帶貨主的清單無法解讀。 */
    public List<Product> listByOwner(UUID ownerId) {
        return productStore.findByOwner(ownerId);
    }
}
