package com.flowzati.archone.inventory.location.entrypoint.response;

import com.flowzati.archone.inventory.location.application.StockLocationView;
import java.util.UUID;

public record StockLocationResponse(UUID locationId, UUID facilityId, String code, String name) {

    public static StockLocationResponse from(StockLocationView location) {
        return new StockLocationResponse(
                location.locationId(), location.facilityId(), location.code(), location.name());
    }
}
