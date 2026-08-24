package com.flowzati.archone.inventory.allocation.application.usecase;

import com.flowzati.archone.foundation.time.BusinessClock;
import com.flowzati.archone.inventory.allocation.application.command.AllocatePendingDemandCommand;
import com.flowzati.archone.inventory.allocation.application.service.reservation.PendingDemandAllocator;
import com.flowzati.archone.inventory.allocation.domain.valueobject.AllocationDemandQueueKey;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Component;

/**
 * Availability wake-up 與 reconciliation 共用的一筆 bounded allocation transaction。
 */
@Component
public class PendingDemandAllocationUsecase {

    private final PendingDemandAllocator pendingDemandAllocator;
    private final BusinessClock appClock;

    public PendingDemandAllocationUsecase(PendingDemandAllocator pendingDemandAllocator, BusinessClock appClock) {
        this.pendingDemandAllocator = pendingDemandAllocator;
        this.appClock = appClock;
    }

    /**
     * 最多 commit 一筆 demand；若 queue 還有 successor，由外層 trigger 再發動下一次 bounded allocation。
     */
    @Transactional
    public boolean execute(AllocatePendingDemandCommand command) {
        AllocationDemandQueueKey queueKey = new AllocationDemandQueueKey(
                command.ownerId(), command.facilityId(), command.locationId(), command.sku());
        return pendingDemandAllocator
                .tryAllocateQueueHead(queueKey, appClock.today(), appClock.instant())
                .isPresent();
    }
}
