package com.flowzati.archone.inventory.allocation.application.service.reservation;

import com.flowzati.archone.foundation.time.BusinessClock;
import com.flowzati.archone.inventory.allocation.application.command.AllocateWaitingDemandCommand;
import com.flowzati.archone.inventory.allocation.domain.valueobject.WaitingAllocationScope;
import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Availability wake-up 與 reconciliation 共用的一筆 bounded allocation transaction。 */
@Component
public class TransactionalAllocationAttempt {

  private final PendingDemandAllocator pendingDemandAllocator;
  private final BusinessClock appClock;
  private final int candidateLimit;

  public TransactionalAllocationAttempt(
      PendingDemandAllocator pendingDemandAllocator,
      BusinessClock appClock,
      @Value("${archone.allocation.waiting-demand-batch-limit:200}") int candidateLimit) {
    if (candidateLimit <= 0) {
      throw new IllegalArgumentException("Waiting-demand allocation limit must be positive");
    }
    this.pendingDemandAllocator = pendingDemandAllocator;
    this.appClock = appClock;
    this.candidateLimit = candidateLimit;
  }

  /** 最多 commit 一筆 demand；若 queue 還有 successor，由外層 trigger 再發動下一次 bounded attempt。 */
  @Transactional
  public boolean attempt(AllocateWaitingDemandCommand command) {
    return pendingDemandAllocator.allocateOne(
        new WaitingAllocationScope(command.ownerId(), command.facilityId(), command.locationId(), command.sku()),
        command.sku(),
        candidateLimit,
        appClock.today(),
        appClock.instant()).isPresent();
  }
}
