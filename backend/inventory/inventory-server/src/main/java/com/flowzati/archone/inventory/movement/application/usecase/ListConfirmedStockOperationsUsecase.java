package com.flowzati.archone.inventory.movement.application.usecase;

import com.flowzati.archone.inventory.movement.application.result.StockOperationView;
import com.flowzati.archone.inventory.movement.application.store.StockOperationViewStore;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 列出等待配貨（CONFIRMED）的出庫作業；可只看單一貨主。 */
@Service
@Transactional(readOnly = true)
public class ListConfirmedStockOperationsUsecase {

    private final StockOperationViewStore stockOperationViewStore;

    public ListConfirmedStockOperationsUsecase(StockOperationViewStore stockOperationViewStore) {
        this.stockOperationViewStore = stockOperationViewStore;
    }

    /** @param ownerId 只列此貨主；{@code null} 表示全部貨主 */
    public List<StockOperationView> list(UUID ownerId, int limit) {
        return ownerId == null
                ? stockOperationViewStore.findConfirmedOutbound(limit)
                : stockOperationViewStore.findConfirmedOutbound(ownerId, limit);
    }
}
