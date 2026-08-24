package com.flowzati.archone.inventory.allocation.domain.valueobject;

import com.flowzati.archone.inventory.allocation.domain.aggregate.AllocationDemand;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** One pending demand and its current position across every required-SKU FIFO queue. */
public record PendingDemandQueuePosition(AllocationDemand demand, List<AllocationQueueHead> requiredQueueHeads) {

    public PendingDemandQueuePosition {
        if (demand == null || requiredQueueHeads == null) {
            throw new IllegalArgumentException("Pending-demand queue position is required");
        }
        requiredQueueHeads = List.copyOf(requiredQueueHeads);
        Map<String, AllocationQueueHead> queueHeadBySku = new HashMap<>();
        for (AllocationQueueHead queueHead : requiredQueueHeads) {
            if (queueHeadBySku.put(queueHead.skuCode(), queueHead) != null) {
                throw new IllegalArgumentException("Pending-demand queue position contains duplicate SKU queue heads");
            }
        }
        if (!queueHeadBySku.keySet().equals(demand.totalsBySku().keySet())) {
            throw new IllegalArgumentException("Pending-demand queue position must contain every required-SKU head");
        }
    }

    public boolean isHeadOfEveryRequiredQueue() {
        return requiredQueueHeads.stream()
                .allMatch(queueHead -> queueHead.allocationDemandId().equals(demand.id()));
    }

    public List<AllocationQueueHead> blockingQueueHeads() {
        return requiredQueueHeads.stream()
                .filter(queueHead -> !queueHead.allocationDemandId().equals(demand.id()))
                .toList();
    }
}
