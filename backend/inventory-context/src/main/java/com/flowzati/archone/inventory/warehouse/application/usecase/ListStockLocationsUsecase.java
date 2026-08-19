package com.flowzati.archone.inventory.warehouse.application.usecase;

import com.flowzati.archone.inventory.warehouse.domain.aggregate.StockLocation;
import com.flowzati.archone.inventory.warehouse.domain.repository.StockLocationRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** 列出一個設施可保存庫存的內部位置，供倉儲操作選擇實際庫存端點。 */
@Service
public class ListStockLocationsUsecase {

    private final StockLocationRepository stockLocationRepository;

    public ListStockLocationsUsecase(StockLocationRepository stockLocationRepository) {
        this.stockLocationRepository = stockLocationRepository;
    }

    public List<StockLocation> listInternalByFacility(UUID facilityId) {
        return stockLocationRepository.findInternalByFacilityId(facilityId);
    }
}
