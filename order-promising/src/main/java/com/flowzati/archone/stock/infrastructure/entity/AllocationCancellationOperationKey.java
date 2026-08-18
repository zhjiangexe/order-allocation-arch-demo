package com.flowzati.archone.stock.infrastructure.entity;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

public class AllocationCancellationOperationKey implements Serializable {

  private UUID allocationDemandId;
  private UUID operationId;

  public AllocationCancellationOperationKey() {
  }

  public AllocationCancellationOperationKey(UUID allocationDemandId, UUID operationId) {
    this.allocationDemandId = allocationDemandId;
    this.operationId = operationId;
  }

  public UUID getAllocationDemandId() {
    return allocationDemandId;
  }

  public UUID getOperationId() {
    return operationId;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof AllocationCancellationOperationKey that
        && Objects.equals(allocationDemandId, that.allocationDemandId)
        && Objects.equals(operationId, that.operationId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(allocationDemandId, operationId);
  }
}
