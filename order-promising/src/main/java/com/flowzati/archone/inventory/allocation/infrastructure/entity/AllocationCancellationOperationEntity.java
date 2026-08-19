package com.flowzati.archone.inventory.allocation.infrastructure.entity;

import com.flowzati.archone.inventory.allocation.domain.type.AllocationCancellationState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@IdClass(AllocationCancellationOperationKey.class)
@Table(name = "allocation_cancellation_operations")
public class AllocationCancellationOperationEntity {

  @Id
  @Column(name = "allocation_demand_id")
  private UUID allocationDemandId;

  @Id
  @Column(name = "operation_id")
  private UUID operationId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private AllocationCancellationState state;

  @Column(name = "started_at", nullable = false)
  private Instant startedAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Version
  @Column(nullable = false)
  private Long version;

  protected AllocationCancellationOperationEntity() {
  }

  public AllocationCancellationOperationEntity(
      UUID allocationDemandId,
      UUID operationId,
      AllocationCancellationState state,
      Instant startedAt,
      Instant updatedAt,
      Long version
  ) {
    this.allocationDemandId = allocationDemandId;
    this.operationId = operationId;
    this.state = state;
    this.startedAt = startedAt;
    this.updatedAt = updatedAt;
    this.version = version;
  }

  public UUID getAllocationDemandId() {
    return allocationDemandId;
  }

  public UUID getOperationId() {
    return operationId;
  }

  public AllocationCancellationState getState() {
    return state;
  }

  public Instant getStartedAt() {
    return startedAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public Long getVersion() {
    return version;
  }
}
