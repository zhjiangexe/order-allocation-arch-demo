package com.flowzati.archone.inventory.allocation.infrastructure.repository;

import com.flowzati.archone.inventory.allocation.application.query.AllocationDemandQueryRepository;
import com.flowzati.archone.inventory.allocation.domain.aggregate.AllocationDemand;
import com.flowzati.archone.inventory.allocation.domain.type.AllocationDemandStatus;
import com.flowzati.archone.inventory.allocation.infrastructure.mapper.AllocationDemandMapper;
import com.flowzati.archone.inventory.allocation.infrastructure.repository.jpa.JpaAllocationDemandRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

@Repository
public class AllocationDemandQueryRepositoryImpl implements AllocationDemandQueryRepository {

    private final JpaAllocationDemandRepository repository;

    public AllocationDemandQueryRepositoryImpl(JpaAllocationDemandRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<AllocationDemand> findPending(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("Pending allocation demand limit must be positive");
        }
        return repository
                .findByStatusOrderByEnqueuedAtAscIdAsc(AllocationDemandStatus.PENDING, PageRequest.of(0, limit))
                .stream()
                .map(AllocationDemandMapper::toDomain)
                .toList();
    }

    @Override
    public Optional<UUID> findBlockingDemandId(AllocationDemand candidate) {
        if (candidate == null) {
            throw new IllegalArgumentException("Pending allocation candidate is required");
        }
        return repository.findBlockingDemandId(
                candidate.ownerId(),
                candidate.facilityId(),
                candidate.locationId(),
                candidate.enqueuedAt(),
                candidate.id(),
                candidate.totalsBySku().keySet());
    }
}
