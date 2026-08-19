package com.flowzati.archone.stock.allocation.domain.event;

import com.flowzati.archone.stock.allocation.domain.valueobject.SourceAllocationUnit;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Source-agnostic allocation completion fact owned by the allocation context. */
public record AllocationCommitted(
    UUID allocationDemandId,
    SourceAllocationUnit source,
    UUID ownerId,
    UUID facilityId,
    UUID sourceLocationId,
    Instant requiredBy,
    int releasePriority,
    Instant occurredAt,
    List<CommittedAllocationMove> moves
) {

  public AllocationCommitted {
    if (allocationDemandId == null || source == null || ownerId == null || facilityId == null
        || sourceLocationId == null || requiredBy == null || occurredAt == null
        || moves == null || moves.isEmpty()) {
      throw new IllegalArgumentException("Allocation completion requires demand, scope, time and moves");
    }
    if (releasePriority < 0 || releasePriority > 100) {
      throw new IllegalArgumentException("Allocation completion requires a valid release priority");
    }
    moves = List.copyOf(moves);
    if (moves.stream().anyMatch(move ->
        !allocationDemandId.equals(move.allocationDemandId())
            || !sourceLocationId.equals(move.sourceLocationId()))) {
      throw new IllegalArgumentException("Completion moves must belong to the allocation demand");
    }
  }

  public record CommittedAllocationMove(
      UUID allocationDemandId,
      UUID allocationDemandLineId,
      String sourceLineId,
      UUID moveId,
      UUID pickingId,
      String skuCode,
      UUID sourceLocationId,
      int quantity,
      List<CommittedBatchPick> batchPicks
  ) {

    public CommittedAllocationMove {
      if (allocationDemandId == null || allocationDemandLineId == null || moveId == null
          || sourceLocationId == null) {
        throw new IllegalArgumentException("A committed move requires allocation-owned identities");
      }
      if (sourceLineId == null || sourceLineId.isBlank()
          || skuCode == null || skuCode.isBlank() || quantity <= 0
          || batchPicks == null || batchPicks.isEmpty()) {
        throw new IllegalArgumentException("A committed move requires source trace, SKU and quantity");
      }
      batchPicks = List.copyOf(batchPicks);
      int committedQuantity;
      try {
        committedQuantity = batchPicks.stream()
            .mapToInt(CommittedBatchPick::quantity).reduce(0, Math::addExact);
      } catch (ArithmeticException overflow) {
        throw new IllegalArgumentException(
            "Committed batch-pick quantity exceeds integer range", overflow);
      }
      if (committedQuantity != quantity) {
        throw new IllegalArgumentException("Committed batch picks must equal the move quantity");
      }
    }
  }

  public record CommittedBatchPick(UUID stockPoolId, int quantity) {
    public CommittedBatchPick {
      if (stockPoolId == null || quantity <= 0) {
        throw new IllegalArgumentException("Committed batch pick requires stock pool and quantity");
      }
    }
  }
}
