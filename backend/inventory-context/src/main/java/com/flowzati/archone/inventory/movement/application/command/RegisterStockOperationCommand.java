package com.flowzati.archone.inventory.movement.application.command;

import com.flowzati.archone.inventory.movement.domain.policy.MovementAssignmentPolicy;
import com.flowzati.archone.inventory.movement.domain.valueobject.StockOperationDirection;
import com.flowzati.archone.inventory.movement.domain.valueobject.StockOperationSource;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Source-neutral command for registering one atomic stock-consuming operation group. */
public record RegisterStockOperationCommand(
        UUID stockOperationTypeId,
        StockOperationDirection direction,
        StockOperationSource source,
        UUID ownerId,
        UUID fromLocationId,
        UUID toLocationId,
        MovementAssignmentPolicy assignmentPolicy,
        Instant enqueuedAt,
        Instant dispatchBy,
        int releasePriority,
        List<MovementLine> lines) {

    public RegisterStockOperationCommand {
        if (stockOperationTypeId == null
                || direction == null
                || direction == StockOperationDirection.INBOUND
                || source == null
                || ownerId == null
                || fromLocationId == null
                || toLocationId == null
                || assignmentPolicy == null
                || enqueuedAt == null
                || dispatchBy == null) {
            throw new IllegalArgumentException(
                    "Movement registration requires operation, source, scope and scheduling");
        }
        if (releasePriority < 0 || releasePriority > 100 || lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("Movement registration requires valid priority and lines");
        }
        HashSet<String> sourceLineIds = new HashSet<>();
        Map<String, Integer> quantityBySku = new LinkedHashMap<>();
        for (MovementLine line : lines) {
            if (!sourceLineIds.add(line.sourceLineId())) {
                throw new IllegalArgumentException("Duplicate source line ID " + line.sourceLineId());
            }
            quantityBySku.merge(line.skuCode(), line.quantity(), Math::addExact);
        }
        lines = lines.stream()
                .sorted(Comparator.comparing(MovementLine::sourceLineId))
                .toList();
    }
}
