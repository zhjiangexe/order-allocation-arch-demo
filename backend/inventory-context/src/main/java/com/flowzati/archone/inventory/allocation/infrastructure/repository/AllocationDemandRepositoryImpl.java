package com.flowzati.archone.inventory.allocation.infrastructure.repository;

import com.flowzati.archone.inventory.allocation.domain.aggregate.AllocationDemand;
import com.flowzati.archone.inventory.allocation.domain.repository.AllocationDemandRepository;
import com.flowzati.archone.inventory.allocation.domain.valueobject.AllocationCandidateBatch;
import com.flowzati.archone.inventory.allocation.domain.valueobject.SourceAllocationUnit;
import com.flowzati.archone.inventory.allocation.domain.valueobject.WaitingAllocationScope;
import com.flowzati.archone.inventory.allocation.infrastructure.mapper.AllocationDemandMapper;
import com.flowzati.archone.inventory.allocation.infrastructure.repository.jpa.JpaAllocationDemandRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Repository;

@Repository
public class AllocationDemandRepositoryImpl implements AllocationDemandRepository {

    private final JpaAllocationDemandRepository repository;

    public AllocationDemandRepositoryImpl(JpaAllocationDemandRepository repository) {
        this.repository = repository;
    }

    @Override
    public AllocationDemand save(AllocationDemand demand) {
        return AllocationDemandMapper.toDomain(repository.save(AllocationDemandMapper.toEntity(demand)));
    }

    @Override
    public Optional<AllocationDemand> findById(UUID allocationDemandId) {
        return repository.findById(allocationDemandId).map(AllocationDemandMapper::toDomain);
    }

    @Override
    public Optional<AllocationDemand> findBySource(SourceAllocationUnit source) {
        return repository
                .findBySourceTypeAndSourceIdAndAllocationUnitKey(
                        source.sourceType(), source.sourceId(), source.allocationUnitKey())
                .map(AllocationDemandMapper::toDomain);
    }

    @Override
    public AllocationCandidateBatch findPendingCandidates(
            WaitingAllocationScope scope, String triggeringSku, int candidateLimit) {
        if (scope == null || triggeringSku == null || triggeringSku.isBlank()) {
            throw new IllegalArgumentException("Allocation candidate scope and triggering SKU are required");
        }
        if (candidateLimit <= 0) {
            throw new IllegalArgumentException("Allocation candidate limit must be positive");
        }
        List<UUID> candidateIds = repository.findTriggeredCandidateIds(
                scope.ownerId(), scope.facilityId(), scope.locationId(), triggeringSku, candidateLimit);
        if (candidateIds.isEmpty()) {
            return AllocationCandidateBatch.empty();
        }
        List<UUID> contextIds =
                repository.findFifoContextIds(scope.ownerId(), scope.facilityId(), scope.locationId(), candidateIds);
        Comparator<AllocationDemand> precedence =
                Comparator.comparing(AllocationDemand::enqueuedAt).thenComparing(AllocationDemand::id);
        Map<UUID, AllocationDemand> byId = repository.findByIdIn(contextIds).stream()
                .map(AllocationDemandMapper::toDomain)
                .collect(Collectors.toMap(AllocationDemand::id, Function.identity()));
        List<AllocationDemand> candidates =
                candidateIds.stream().map(byId::get).sorted(precedence).toList();
        List<AllocationDemand> context =
                byId.values().stream().sorted(precedence).toList();
        return new AllocationCandidateBatch(candidates, context);
    }

    @Override
    public List<WaitingAllocationScope> findAllocatablePendingScopes(java.time.LocalDate today, int limit) {
        if (today == null || limit <= 0) {
            throw new IllegalArgumentException("Pending allocation scope date and positive limit are required");
        }
        return repository.findAllocatablePendingScopes(today, limit).stream()
                .map(scope -> new WaitingAllocationScope(
                        scope.getOwnerId(), scope.getFacilityId(), scope.getLocationId(), scope.getSkuCode()))
                .toList();
    }

    @Override
    public List<UUID> findPendingExecutionAnomalyIds(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("Allocation anomaly limit must be positive");
        }
        return repository.findPendingExecutionAnomalyIds(limit);
    }
}
