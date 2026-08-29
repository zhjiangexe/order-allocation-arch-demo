package com.flowzati.archone.inventory.location.application.repo;

import com.flowzati.archone.inventory.location.application.StockLocationView;
import java.util.List;
import java.util.UUID;

/** Focused Store for operator-facing stock-location projections. */
public interface StockLocationViewStore {

    List<StockLocationView> findInternalByFacilityId(UUID facilityId);
}
