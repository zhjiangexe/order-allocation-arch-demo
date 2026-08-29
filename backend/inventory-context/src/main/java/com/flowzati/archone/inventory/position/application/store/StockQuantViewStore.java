package com.flowzati.archone.inventory.position.application.store;

import com.flowzati.archone.inventory.position.application.StockQuantView;
import java.util.List;
import java.util.UUID;

/** Focused Store for the operator-facing stock-by-location projection. */
public interface StockQuantViewStore {

    /** Returns an immutable list ordered by SKU, expiry date, in-date and stock-quant identity. */
    List<StockQuantView> findBatchesInLocation(UUID ownerId, UUID locationId);
}
