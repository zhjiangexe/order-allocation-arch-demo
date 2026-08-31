package com.flowzati.archone.inventory.allocation.application.service;

import com.flowzati.archone.inventory.allocation.application.result.AssignedMove;
import com.flowzati.archone.inventory.allocation.application.result.AssignedMoveLine;
import com.flowzati.archone.inventory.allocation.application.result.StockOperationAssignmentResult;
import com.flowzati.archone.inventory.allocation.domain.entity.StockMoveLine;
import com.flowzati.archone.inventory.movement.application.store.StockOperationTypeStore;
import com.flowzati.archone.inventory.movement.domain.aggregate.StockMove;
import com.flowzati.archone.inventory.movement.domain.aggregate.StockOperation;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Builds the committed assignment view from one operation and its authoritative move lines. */
@Component
public final class StockOperationAssignmentResultFactory {

    private final StockOperationTypeStore stockOperationTypeStore;

    public StockOperationAssignmentResultFactory(StockOperationTypeStore stockOperationTypeStore) {
        this.stockOperationTypeStore = stockOperationTypeStore;
    }

    public StockOperationAssignmentResult create(
            StockOperation operation, List<StockMove> moves, List<StockMoveLine> moveLines, Instant assignedAt) {
        Map<UUID, List<StockMoveLine>> moveLinesByMove = moveLines.stream()
                .sorted(Comparator.comparing(StockMoveLine::stockQuantId))
                .collect(Collectors.groupingBy(
                        StockMoveLine::moveId, LinkedHashMap::new, Collectors.toCollection(ArrayList::new)));
        List<AssignedMove> assignedMoves = moves.stream()
                .sorted(Comparator.comparingInt(StockMove::getLineSequence).thenComparing(StockMove::getId))
                .map(move -> new AssignedMove(
                        move.getId(),
                        move.getSourceLineId(),
                        move.getLineSequence(),
                        move.getSkuCode(),
                        move.getDemandQuantity(),
                        moveLinesByMove.getOrDefault(move.getId(), List.of()).stream()
                                .map(moveLine -> new AssignedMoveLine(moveLine.stockQuantId(), moveLine.quantity()))
                                .toList()))
                .toList();
        if (moveLinesByMove.keySet().stream()
                .anyMatch(moveId ->
                        assignedMoves.stream().noneMatch(move -> move.moveId().equals(moveId)))) {
            throw new IllegalStateException("Assigned stock operation contains a move line for a foreign stock move");
        }
        return new StockOperationAssignmentResult(
                operation.id(),
                operation.stockOperationTypeId(),
                stockOperationTypeStore
                        .findById(operation.stockOperationTypeId())
                        .orElseThrow(() -> new IllegalStateException(
                                "Stock operation type no longer exists: " + operation.stockOperationTypeId()))
                        .facilityId(),
                operation.source(),
                operation.ownerId(),
                operation.fromLocationId(),
                operation.toLocationId(),
                operation.assignmentPolicy(),
                operation.dispatchBy(),
                operation.releasePriority(),
                assignedAt,
                assignedMoves);
    }
}
