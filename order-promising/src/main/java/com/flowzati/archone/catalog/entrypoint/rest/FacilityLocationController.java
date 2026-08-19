package com.flowzati.archone.catalog.entrypoint.rest;

import com.flowzati.archone.catalog.application.usecase.ListStockLocationsUsecase;
import com.flowzati.archone.catalog.entrypoint.rest.response.StockLocationResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 設施內部庫位的唯讀主檔入口。 */
@RestController
@RequestMapping("/facilities")
public class FacilityLocationController {

    private final ListStockLocationsUsecase listStockLocationsUsecase;

    public FacilityLocationController(ListStockLocationsUsecase listStockLocationsUsecase) {
        this.listStockLocationsUsecase = listStockLocationsUsecase;
    }

    @GetMapping("/{facilityId}/locations")
    public List<StockLocationResponse> listInternalLocations(@PathVariable UUID facilityId) {
        return listStockLocationsUsecase.listInternalByFacility(facilityId).stream()
                .map(StockLocationResponse::from)
                .toList();
    }
}
