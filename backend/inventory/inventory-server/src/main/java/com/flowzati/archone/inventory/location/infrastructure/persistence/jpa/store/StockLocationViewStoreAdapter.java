package com.flowzati.archone.inventory.location.infrastructure.persistence.jpa.store;

import com.flowzati.archone.inventory.location.application.store.StockLocationViewStore;
import com.flowzati.archone.inventory.location.application.view.StockLocationView;
import com.flowzati.archone.inventory.location.domain.valueobject.LocationUsageType;
import com.flowzati.archone.inventory.location.infrastructure.persistence.jpa.repository.JpaStockLocationRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/** Query adapter for the facility's operator-facing internal-location list. */
@Repository
public class StockLocationViewStoreAdapter implements StockLocationViewStore {

    private final JpaStockLocationRepository jpaStockLocationRepository;

    public StockLocationViewStoreAdapter(JpaStockLocationRepository jpaStockLocationRepository) {
        this.jpaStockLocationRepository = jpaStockLocationRepository;
    }

    @Override
    public List<StockLocationView> findInternalByFacilityId(UUID facilityId) {
        if (facilityId == null) {
            return List.of();
        }
        return jpaStockLocationRepository
                .findAllByFacilityIdAndUsageOrderByCode(facilityId, LocationUsageType.INTERNAL)
                .stream()
                .map(location -> new StockLocationView(
                        location.getId(), location.getFacilityId(), location.getCode(), location.getName()))
                .toList();
    }
}
