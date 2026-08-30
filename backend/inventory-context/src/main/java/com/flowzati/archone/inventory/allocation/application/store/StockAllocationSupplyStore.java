package com.flowzati.archone.inventory.allocation.application.store;

import com.flowzati.archone.inventory.allocation.domain.valueobject.StockAllocationSupply;
import java.time.LocalDate;
import java.util.Collection;
import java.util.UUID;

/** Loads one allocation-owned immutable FEFO supply projection for an owner and source location. */
public interface StockAllocationSupplyStore {

    StockAllocationSupply findBySku(UUID ownerId, UUID locationId, Collection<String> skuCodes, LocalDate today);
}
