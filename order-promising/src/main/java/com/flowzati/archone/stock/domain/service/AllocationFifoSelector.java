package com.flowzati.archone.stock.domain.service;

import com.flowzati.archone.stock.domain.model.AllocationCandidateBatch;
import com.flowzati.archone.stock.domain.model.AllocationDemand;
import java.util.Comparator;
import java.util.Optional;

/** 在 shared-SKU strict FIFO queues 中選出本輪可以前進的第一筆 demand。 */
public class AllocationFifoSelector {

  private static final Comparator<AllocationDemand> PRECEDENCE = Comparator
      .comparing(AllocationDemand::enqueuedAt)
      .thenComparing(AllocationDemand::id);

  /**
   * Candidate 必須在每個 required SKU queue 都位於 head-of-line。
   * 不共享 SKU 的 demand 不會互相阻擋。
   */
  public Optional<AllocationDemand> selectFirstEligible(AllocationCandidateBatch batch) {
    return batch.candidates().stream()
        .sorted(PRECEDENCE)
        .filter(candidate -> isHeadOfEveryRequiredSku(candidate, batch))
        .findFirst();
  }

  private static boolean isHeadOfEveryRequiredSku(
      AllocationDemand candidate, AllocationCandidateBatch batch) {
    return candidate.totalsBySku().keySet().stream()
        .allMatch(skuCode -> isQueueHead(candidate, skuCode, batch));
  }

  private static boolean isQueueHead(
      AllocationDemand candidate, String skuCode, AllocationCandidateBatch batch) {
    return batch.fifoContext().stream()
        .filter(other -> other.totalsBySku().containsKey(skuCode))
        .min(PRECEDENCE)
        .map(head -> head.id().equals(candidate.id()))
        .orElse(false);
  }
}
