package com.flowzati.archone.inventory.movement.application.usecase;

import com.flowzati.archone.inventory.movement.application.result.StockOperationView;
import com.flowzati.archone.inventory.movement.application.store.StockOperationViewStore;
import com.flowzati.archone.inventory.movement.domain.valueobject.StockOperationSource;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 依訂單編號查出該訂單主要來源的庫存作業。 */
@Service
@Transactional(readOnly = true)
public class GetPrimaryOrderStockOperationUsecase {

    private final StockOperationViewStore stockOperationViewStore;

    public GetPrimaryOrderStockOperationUsecase(StockOperationViewStore stockOperationViewStore) {
        this.stockOperationViewStore = stockOperationViewStore;
    }

    public Optional<StockOperationView> get(UUID orderId) {
        if (orderId == null) {
            throw new IllegalArgumentException("Order ID is required");
        }
        return stockOperationViewStore.findBySource(StockOperationSource.primaryOrder(orderId.toString()));
    }
}
