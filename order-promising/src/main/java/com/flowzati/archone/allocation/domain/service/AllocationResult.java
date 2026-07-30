package com.flowzati.archone.allocation.domain.service;

import java.util.List;

/**
 * 一張訂單的配貨結果：成功與否，以及成功時取用了哪些批、各取多少。
 *
 * <p>配不到時 {@code picks} 必為空——ship-complete 之下「取了一部分但沒配到」不是合法狀態。
 */
public record AllocationResult(AllocationOutcome outcome, List<BatchPick> picks) {

  public AllocationResult {
    if (outcome == null) {
      throw new IllegalArgumentException("Outcome is required");
    }
    picks = List.copyOf(picks);
    if (outcome != AllocationOutcome.ALLOCATED && !picks.isEmpty()) {
      throw new IllegalArgumentException("A non-allocated result cannot carry picks");
    }
    if (outcome == AllocationOutcome.ALLOCATED && picks.isEmpty()) {
      throw new IllegalArgumentException("An allocated result must carry picks");
    }
  }

  public static AllocationResult allocated(List<BatchPick> picks) {
    return new AllocationResult(AllocationOutcome.ALLOCATED, picks);
  }

  public static AllocationResult notAllocated(AllocationOutcome outcome) {
    return new AllocationResult(outcome, List.of());
  }

  public boolean isAllocated() {
    return outcome == AllocationOutcome.ALLOCATED;
  }
}
