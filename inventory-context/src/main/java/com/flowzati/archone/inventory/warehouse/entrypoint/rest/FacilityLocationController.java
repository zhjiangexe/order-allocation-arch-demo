package com.flowzati.archone.inventory.warehouse.entrypoint.rest;

import com.flowzati.archone.inventory.warehouse.application.usecase.ListStockLocationsUsecase;
import com.flowzati.archone.inventory.warehouse.entrypoint.rest.response.StockLocationResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 設施內部庫位的唯讀倉儲入口；URL 維持以 Facility 識別對外查詢。 */
@RestController
@RequestMapping("/facilities")
public class FacilityLocationController {

    private final ListStockLocationsUsecase listStockLocationsUsecase;

    public FacilityLocationController(ListStockLocationsUsecase listStockLocationsUsecase) {
        this.listStockLocationsUsecase = listStockLocationsUsecase;
    }

    @GetMapping("/{facilityId}/locations")
    public List<StockLocationResponse> listInternalLocations(@PathVariable(name = "facilityId") UUID facilityId) {
        return listStockLocationsUsecase.listInternalByFacility(facilityId).stream()
                .map(StockLocationResponse::from)
                .toList();
    }
}
