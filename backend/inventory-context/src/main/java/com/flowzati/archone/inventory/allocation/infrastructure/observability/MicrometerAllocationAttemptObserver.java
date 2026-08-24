package com.flowzati.archone.inventory.allocation.infrastructure.observability;

import com.flowzati.archone.inventory.allocation.application.service.reservation.AllocationAttemptObserver;
import com.flowzati.archone.inventory.allocation.domain.aggregate.AllocationDemand;
import com.flowzati.archone.inventory.allocation.domain.valueobject.AllocationQueueHead;
import com.flowzati.archone.inventory.allocation.domain.valueobject.PendingDemandQueuePosition;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 用 Micrometer 與 structured log 記錄 strict-FIFO head-of-line blocking。 */
@Component
public class MicrometerAllocationAttemptObserver implements AllocationAttemptObserver {

    private static final Logger log = LoggerFactory.getLogger(MicrometerAllocationAttemptObserver.class);
    private final MeterRegistry meters;

    public MicrometerAllocationAttemptObserver(MeterRegistry meters) {
        this.meters = meters;
    }

    @Override
    public void recordBlocked(PendingDemandQueuePosition position, Instant observedAt) {
        AllocationDemand candidate = position.demand();
        position.blockingQueueHeads().forEach(queueHead -> record(candidate, queueHead, observedAt));
    }

    private void record(AllocationDemand blocked, AllocationQueueHead queueHead, Instant observedAt) {
        meters.counter(
                        "allocation_fifo_blocked_total",
                        "source_type",
                        blocked.source().sourceType().name(),
                        "sku",
                        queueHead.skuCode())
                .increment();
        meters.timer(
                        "allocation_fifo_pending_age",
                        "source_type",
                        queueHead.sourceType().name(),
                        "blocked_sku",
                        queueHead.skuCode())
                .record(Duration.between(queueHead.enqueuedAt(), observedAt).abs());
        log.atWarn()
                .addKeyValue("allocationDemandId", blocked.id())
                .addKeyValue("blockingPredecessorId", queueHead.allocationDemandId())
                .addKeyValue("blockedSku", queueHead.skuCode())
                .addKeyValue("predecessorEnqueuedAt", queueHead.enqueuedAt())
                .log("Allocation demand blocked by strict shared-SKU FIFO predecessor");
    }
}
