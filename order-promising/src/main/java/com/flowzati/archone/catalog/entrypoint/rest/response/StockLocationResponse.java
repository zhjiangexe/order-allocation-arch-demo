package com.flowzati.archone.catalog.entrypoint.rest.response;

import com.flowzati.archone.catalog.domain.aggregate.StockLocation;
import java.util.UUID;

public record StockLocationResponse(UUID locationId, UUID facilityId, String code, String name) {

    public static StockLocationResponse from(StockLocation location) {
        return new StockLocationResponse(
                location.getId(), location.getFacilityId(), location.getCode(), location.getName());
    }
}
