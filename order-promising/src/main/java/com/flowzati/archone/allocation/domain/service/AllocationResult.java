package com.flowzati.archone.allocation.domain.service;

import java.util.List;

/**
 * 一張訂單的配貨結果：成功與否，以及成功時取用了哪些批、各取多少。
 *
 * <p>配不到時 {@code picks} 必為空——ship-complete 之下「取了一部分但沒配到」不是合法狀態。
 *
 * <p>配不到時另外帶 {@code shortfall}：**每一個**不足的 SKU 各差幾件。{@code outcome} 回答
 * 「配到了嗎、是哪一類的配不到」，{@code shortfall} 回答「差在哪」——把後者塞進 enum 會讓它
 * 變成無界的。單 SKU 時這個資訊可以去庫存頁拼出來，多 SKU 之後拼不出來：使用者得逐一查每個
 * SKU 才知道整張單卡在哪一項。
 */
public record AllocationResult(
    AllocationOutcome outcome, List<BatchPick> picks, SkuQuantities shortfall) {

  public AllocationResult {
    if (outcome == null) {
      throw new IllegalArgumentException("Outcome is required");
    }
    if (shortfall == null) {
      throw new IllegalArgumentException("Shortfall is required");
    }
    picks = List.copyOf(picks);
    if (outcome == AllocationOutcome.ALLOCATED && !shortfall.isEmpty()) {
      throw new IllegalArgumentException("An allocated result cannot be short of anything");
    }
    if (outcome != AllocationOutcome.ALLOCATED && !picks.isEmpty()) {
      throw new IllegalArgumentException("A non-allocated result cannot carry picks");
    }
    if (outcome == AllocationOutcome.ALLOCATED && picks.isEmpty()) {
      throw new IllegalArgumentException("An allocated result must carry picks");
    }
  }

  public static AllocationResult allocated(List<BatchPick> picks) {
    return new AllocationResult(AllocationOutcome.ALLOCATED, picks, SkuQuantities.empty());
  }

  public static AllocationResult notAllocated(AllocationOutcome outcome, SkuQuantities shortfall) {
    return new AllocationResult(outcome, List.of(), shortfall);
  }

  public boolean isAllocated() {
    return outcome == AllocationOutcome.ALLOCATED;
  }
}
