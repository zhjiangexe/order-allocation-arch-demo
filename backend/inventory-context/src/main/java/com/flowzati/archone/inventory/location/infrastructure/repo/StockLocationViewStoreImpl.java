package com.flowzati.archone.inventory.location.infrastructure.repo;

import com.flowzati.archone.inventory.location.application.StockLocationView;
import com.flowzati.archone.inventory.location.application.repo.StockLocationViewStore;
import com.flowzati.archone.inventory.location.domain.LocationUsageType;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/** Query adapter for the facility's operator-facing internal-location list. */
@Repository
public class StockLocationViewStoreImpl implements StockLocationViewStore {

    private final JpaStockLocationRepository jpaStockLocationRepository;

    public StockLocationViewStoreImpl(JpaStockLocationRepository jpaStockLocationRepository) {
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
