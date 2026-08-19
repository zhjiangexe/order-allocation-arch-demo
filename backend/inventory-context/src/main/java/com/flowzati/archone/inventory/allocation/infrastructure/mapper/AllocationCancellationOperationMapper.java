package com.flowzati.archone.inventory.allocation.infrastructure.mapper;

import com.flowzati.archone.inventory.allocation.domain.aggregate.AllocationCancellationOperation;
import com.flowzati.archone.inventory.allocation.infrastructure.entity.AllocationCancellationOperationEntity;

public final class AllocationCancellationOperationMapper {

    private AllocationCancellationOperationMapper() {}

    public static AllocationCancellationOperationEntity toEntity(AllocationCancellationOperation operation) {
        return new AllocationCancellationOperationEntity(
                operation.allocationDemandId(),
                operation.operationId(),
                operation.state(),
                operation.startedAt(),
                operation.updatedAt(),
                operation.version());
    }

    public static AllocationCancellationOperation toDomain(AllocationCancellationOperationEntity entity) {
        return AllocationCancellationOperation.rehydrate(
                entity.getAllocationDemandId(),
                entity.getOperationId(),
                entity.getStartedAt(),
                entity.getUpdatedAt(),
                entity.getState(),
                entity.getVersion());
    }
}
