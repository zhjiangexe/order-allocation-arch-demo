package com.flowzati.archone.inventory.allocation.infrastructure.repository;

import com.flowzati.archone.inventory.allocation.domain.aggregate.AllocationCancellationOperation;
import com.flowzati.archone.inventory.allocation.domain.repository.AllocationCancellationOperationRepository;
import com.flowzati.archone.inventory.allocation.infrastructure.entity.AllocationCancellationOperationKey;
import com.flowzati.archone.inventory.allocation.infrastructure.mapper.AllocationCancellationOperationMapper;
import com.flowzati.archone.inventory.allocation.infrastructure.repository.jpa.JpaAllocationCancellationOperationRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class AllocationCancellationOperationRepositoryImpl implements AllocationCancellationOperationRepository {

    private final JpaAllocationCancellationOperationRepository repository;

    public AllocationCancellationOperationRepositoryImpl(JpaAllocationCancellationOperationRepository repository) {
        this.repository = repository;
    }

    @Override
    public AllocationCancellationOperation save(AllocationCancellationOperation operation) {
        return AllocationCancellationOperationMapper.toDomain(
                repository.save(AllocationCancellationOperationMapper.toEntity(operation)));
    }

    @Override
    public Optional<AllocationCancellationOperation> find(UUID allocationDemandId, UUID operationId) {
        return repository
                .findById(new AllocationCancellationOperationKey(allocationDemandId, operationId))
                .map(AllocationCancellationOperationMapper::toDomain);
    }
}
