package com.flowzati.archone.inventory.allocation.infrastructure.repository;

import com.flowzati.archone.inventory.allocation.application.query.PendingDemandBacklogQuery;
import com.flowzati.archone.inventory.allocation.domain.valueobject.AllocationDemandQueueKey;
import com.flowzati.archone.inventory.allocation.infrastructure.repository.jpa.JpaAllocationDemandRepository;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Repository;

@Repository
public class PendingDemandBacklogQueryImpl implements PendingDemandBacklogQuery {

    private final JpaAllocationDemandRepository repository;

    public PendingDemandBacklogQueryImpl(JpaAllocationDemandRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<AllocationDemandQueueKey> findQueueKeysWithAvailableStock(LocalDate today, int limit) {
        if (today == null || limit <= 0) {
            throw new IllegalArgumentException("Pending-demand queue date and positive limit are required");
        }
        return repository.findPendingQueueKeysWithAvailableStock(today, limit).stream()
                .map(key -> new AllocationDemandQueueKey(
                        key.getOwnerId(), key.getFacilityId(), key.getLocationId(), key.getSkuCode()))
                .toList();
    }
}
