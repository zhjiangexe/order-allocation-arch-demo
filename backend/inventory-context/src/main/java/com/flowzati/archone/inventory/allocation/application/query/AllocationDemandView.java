package com.flowzati.archone.inventory.allocation.application.query;

import com.flowzati.archone.inventory.allocation.domain.type.AllocationDemandStatus;
import com.flowzati.archone.inventory.allocation.domain.type.AllocationSourceType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** DEMO 與維運查詢使用的 demand、供需與 execution 綜合唯讀視圖。 */
public record AllocationDemandView(
        UUID allocationDemandId,
        AllocationSourceType sourceType,
        String sourceId,
        String allocationUnitKey,
        UUID ownerId,
        UUID facilityId,
        UUID locationId,
        Instant requiredBy,
        int releasePriority,
        Instant enqueuedAt,
        AllocationDemandStatus status,
        AllocationWaitingReason waitingReason,
        UUID blockedByAllocationDemandId,
        Map<String, Integer> requiredQuantities,
        Map<String, Integer> availableQuantities,
        Map<String, Integer> missingQuantities,
        List<AllocationDemandLineView> lines,
        List<AllocationPickingView> pickings,
        List<AllocationMoveView> moves) {

    public AllocationDemandView {
        requiredQuantities = Map.copyOf(requiredQuantities);
        availableQuantities = Map.copyOf(availableQuantities);
        missingQuantities = Map.copyOf(missingQuantities);
        lines = List.copyOf(lines);
        pickings = List.copyOf(pickings);
        moves = List.copyOf(moves);
    }
}
