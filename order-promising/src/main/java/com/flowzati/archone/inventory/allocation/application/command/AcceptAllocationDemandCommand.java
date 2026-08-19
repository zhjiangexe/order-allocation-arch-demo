package com.flowzati.archone.inventory.allocation.application.command;

import com.flowzati.archone.inventory.allocation.domain.valueobject.AllocationDemandLineRequest;
import com.flowzati.archone.inventory.allocation.domain.valueobject.SourceAllocationUnit;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/**
 * V1 正規化來源契約：描述一個可獨立配置的 allocation unit。
 *
 * <p>所有 source adapter 都先轉成這個 command，後面的 acceptance/planner/committer 因此不需要
 * 依賴 Order、Transfer 或其他來源 aggregate。
 */
public record AcceptAllocationDemandCommand(
        SourceAllocationUnit source,
        UUID ownerId,
        UUID facilityId,
        UUID sourceLocationId,
        Instant requiredBy,
        int releasePriority,
        Instant enqueuedAt,
        List<SourceDemandLine> lines,
        AllocationExecutionIntent executionIntent) {

    public AcceptAllocationDemandCommand {
        if (source == null
                || ownerId == null
                || facilityId == null
                || sourceLocationId == null
                || requiredBy == null
                || enqueuedAt == null
                || executionIntent == null) {
            throw new IllegalArgumentException("Demand acceptance requires identity, scope and snapshots");
        }
        if (releasePriority < 0 || releasePriority > 100 || lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("Demand acceptance requires valid priority and lines");
        }
        if (!sourceLocationId.equals(executionIntent.fromLocationId())) {
            throw new IllegalArgumentException("Demand and execution must use the same source location");
        }
        lines = List.copyOf(lines);
        HashSet<String> sourceLineIds = new HashSet<>();
        for (SourceDemandLine line : lines) {
            if (!sourceLineIds.add(line.sourceLineId())) {
                throw new IllegalArgumentException("Duplicate source line ID " + line.sourceLineId());
            }
        }
    }

    public List<AllocationDemandLineRequest> demandLines() {
        return lines.stream()
                .map(line -> new AllocationDemandLineRequest(line.sourceLineId(), line.skuCode(), line.quantity()))
                .toList();
    }

    public record SourceDemandLine(String sourceLineId, String skuCode, int quantity, UUID sourceLineReferenceId) {

        public SourceDemandLine {
            // AllocationDemandLineRequest owns the common validation and keeps both paths identical.
            new AllocationDemandLineRequest(sourceLineId, skuCode, quantity);
        }
    }
}
