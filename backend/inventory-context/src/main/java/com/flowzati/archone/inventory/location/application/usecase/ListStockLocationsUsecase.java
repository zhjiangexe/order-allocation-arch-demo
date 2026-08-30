package com.flowzati.archone.inventory.location.application.usecase;

import com.flowzati.archone.inventory.location.application.store.StockLocationViewStore;
import com.flowzati.archone.inventory.location.application.view.StockLocationView;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** 列出一個設施可保存庫存的內部位置，供倉儲操作選擇實際庫存端點。 */
@Service
public class ListStockLocationsUsecase {

    private final StockLocationViewStore stockLocationViewStore;

    public ListStockLocationsUsecase(StockLocationViewStore stockLocationViewStore) {
        this.stockLocationViewStore = stockLocationViewStore;
    }

    public List<StockLocationView> listInternalByFacility(UUID facilityId) {
        return stockLocationViewStore.findInternalByFacilityId(facilityId);
    }
}
