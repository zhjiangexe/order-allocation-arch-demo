package com.flowzati.archone.inventory.location.application.store;

import com.flowzati.archone.inventory.location.application.result.StockLocationView;
import java.util.List;
import java.util.UUID;

/** Focused Store for operator-facing stock-location projections. */
public interface StockLocationViewStore {

    List<StockLocationView> findInternalByFacilityId(UUID facilityId);
}
