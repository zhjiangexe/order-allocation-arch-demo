package com.flowzati.archone.inventory.location.application.store;

import com.flowzati.archone.inventory.location.domain.entity.StockLocation;
import java.util.Optional;
import java.util.UUID;

/** Application-owned data-access boundary for canonical stock locations. */
public interface StockLocationStore {

    Optional<StockLocation> findById(UUID locationId);

    void save(StockLocation location);
}
