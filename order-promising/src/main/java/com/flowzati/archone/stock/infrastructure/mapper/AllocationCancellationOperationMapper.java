package com.flowzati.archone.stock.infrastructure.mapper;

import com.flowzati.archone.stock.domain.model.AllocationCancellationOperation;
import com.flowzati.archone.stock.infrastructure.entity.AllocationCancellationOperationEntity;

public final class AllocationCancellationOperationMapper {

  private AllocationCancellationOperationMapper() {
  }

  public static AllocationCancellationOperationEntity toEntity(
      AllocationCancellationOperation operation) {
    return new AllocationCancellationOperationEntity(
        operation.allocationDemandId(), operation.operationId(), operation.state(),
        operation.startedAt(), operation.updatedAt(), operation.version());
  }

  public static AllocationCancellationOperation toDomain(
      AllocationCancellationOperationEntity entity) {
    return AllocationCancellationOperation.rehydrate(
        entity.getAllocationDemandId(), entity.getOperationId(), entity.getStartedAt(),
        entity.getUpdatedAt(), entity.getState(), entity.getVersion());
  }
}
