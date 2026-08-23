package com.flowzati.archone.inventory.allocation.infrastructure.repository;

import com.flowzati.archone.inventory.allocation.domain.aggregate.AllocationDemand;
import com.flowzati.archone.inventory.allocation.domain.repository.AllocationDemandRepository;
import com.flowzati.archone.inventory.allocation.domain.type.AllocationDemandStatus;
import com.flowzati.archone.inventory.allocation.domain.valueobject.AllocationCandidateBatch;
import com.flowzati.archone.inventory.allocation.domain.valueobject.AllocationDemandQueueKey;
import com.flowzati.archone.inventory.allocation.domain.valueobject.SourceAllocationUnit;
import com.flowzati.archone.inventory.allocation.infrastructure.mapper.AllocationDemandMapper;
import com.flowzati.archone.inventory.allocation.infrastructure.repository.jpa.JpaAllocationDemandRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
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
    public List<AllocationDemand> findPendingThrough(java.time.Instant enqueuedAt, UUID allocationDemandId) {
        if (enqueuedAt == null || allocationDemandId == null) {
            throw new IllegalArgumentException("Pending allocation demand precedence is required");
        }
        List<UUID> ids = repository.findPendingIdsThrough(enqueuedAt, allocationDemandId);
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<UUID, AllocationDemand> byId = repository.findByIdIn(ids).stream()
                .map(AllocationDemandMapper::toDomain)
                .collect(Collectors.toMap(AllocationDemand::id, Function.identity()));
        return ids.stream().map(byId::get).toList();
    }

    @Override
    public AllocationCandidateBatch findPendingCandidates(AllocationDemandQueueKey queueKey, int candidateLimit) {
        if (queueKey == null) {
            throw new IllegalArgumentException("Pending-demand queue key is required");
        }
        if (candidateLimit <= 0) {
            throw new IllegalArgumentException("Allocation candidate limit must be positive");
        }
        List<UUID> candidateIds = repository.findPendingQueueCandidateIds(
                queueKey.ownerId(), queueKey.facilityId(), queueKey.locationId(), queueKey.skuCode(), candidateLimit);
        if (candidateIds.isEmpty()) {
            return AllocationCandidateBatch.empty();
        }
        List<UUID> contextIds = repository.findFifoContextIds(
                queueKey.ownerId(), queueKey.facilityId(), queueKey.locationId(), candidateIds);
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
    public List<AllocationDemandQueueKey> findAllocatablePendingQueueKeys(java.time.LocalDate today, int limit) {
        if (today == null || limit <= 0) {
            throw new IllegalArgumentException("Pending-demand queue date and positive limit are required");
        }
        return repository.findAllocatablePendingQueueKeys(today, limit).stream()
                .map(key -> new AllocationDemandQueueKey(
                        key.getOwnerId(), key.getFacilityId(), key.getLocationId(), key.getSkuCode()))
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
