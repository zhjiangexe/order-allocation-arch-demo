package com.flowzati.archone.catalog.application.usecase;

import com.flowzati.archone.catalog.domain.aggregate.StockLocation;
import com.flowzati.archone.catalog.domain.repository.StockLocationRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** 列出一個設施可保存庫存的內部位置，供操作台選擇實際庫存端點。 */
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
