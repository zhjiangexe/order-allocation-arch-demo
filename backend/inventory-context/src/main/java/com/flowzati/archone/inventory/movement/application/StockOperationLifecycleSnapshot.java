package com.flowzati.archone.inventory.movement.application;

import com.flowzati.archone.inventory.movement.domain.StockOperationSource;
import com.flowzati.archone.inventory.movement.domain.aggregate.StockMove;
import com.flowzati.archone.inventory.movement.domain.aggregate.StockOperation;
import com.flowzati.archone.inventory.reservation.domain.StockMoveLine;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** Immutable before-image retained when lifecycle processing removes current reservation detail. */
public record StockOperationLifecycleSnapshot(
        UUID stockOperationId,
        UUID stockOperationTypeId,
        StockOperationSource source,
        List<MoveSnapshot> moves,
        Instant occurredAt) {

    public StockOperationLifecycleSnapshot {
        if (stockOperationId == null
                || stockOperationTypeId == null
                || source == null
                || moves == null
                || moves.isEmpty()
                || occurredAt == null) {
            throw new IllegalArgumentException(
                    "Lifecycle snapshot requires operation, operation type, source, moves and time");
        }
        moves = List.copyOf(moves);
        HashSet<UUID> moveIds = new HashSet<>();
        if (moves.stream().anyMatch(move -> move == null || !moveIds.add(move.moveId()))) {
            throw new IllegalArgumentException("Lifecycle snapshot requires unique non-null moves");
        }
    }

    public static StockOperationLifecycleSnapshot capture(
            StockOperation operation, List<StockMove> moves, List<StockMoveLine> lines, Instant occurredAt) {
        if (operation.source() == null) {
            throw new IllegalStateException("Lifecycle audit requires a stock-consuming source identity");
        }
        Map<UUID, List<StockMoveLine>> linesByMove = lines.stream()
                .sorted(Comparator.comparing(StockMoveLine::stockQuantId))
                .collect(Collectors.groupingBy(
                        StockMoveLine::moveId, LinkedHashMap::new, Collectors.toCollection(ArrayList::new)));
        List<MoveSnapshot> snapshots = moves.stream()
                .sorted(Comparator.comparingInt(StockMove::getLineSequence).thenComparing(StockMove::getId))
                .map(move -> new MoveSnapshot(
                        move.getId(),
                        move.getSourceLineId(),
                        move.getSkuCode(),
                        move.getDemandQuantity(),
                        linesByMove.getOrDefault(move.getId(), List.of()).stream()
                                .map(line -> new MoveLineSnapshot(line.stockQuantId(), line.quantity()))
                                .toList()))
                .toList();
        if (linesByMove.keySet().stream()
                .anyMatch(moveId ->
                        snapshots.stream().noneMatch(move -> move.moveId().equals(moveId)))) {
            throw new IllegalStateException("Lifecycle snapshot contains detail for a foreign movement");
        }
        return new StockOperationLifecycleSnapshot(
                operation.id(), operation.stockOperationTypeId(), operation.source(), snapshots, occurredAt);
    }

    public record MoveSnapshot(
            UUID moveId, String sourceLineId, String skuCode, int quantity, List<MoveLineSnapshot> moveLines) {

        public MoveSnapshot {
            if (moveId == null
                    || sourceLineId == null
                    || sourceLineId.isBlank()
                    || skuCode == null
                    || skuCode.isBlank()
                    || quantity <= 0
                    || moveLines == null) {
                throw new IllegalArgumentException("Lifecycle move snapshot requires source line, SKU and quantity");
            }
            moveLines = List.copyOf(moveLines);
            int covered =
                    moveLines.stream().mapToInt(MoveLineSnapshot::quantity).reduce(0, Math::addExact);
            if (covered != 0 && covered != quantity) {
                throw new IllegalArgumentException("Lifecycle move lines must be empty or exactly cover the move");
            }
        }
    }

    public record MoveLineSnapshot(UUID stockQuantId, int quantity) {

        public MoveLineSnapshot {
            if (stockQuantId == null || quantity <= 0) {
                throw new IllegalArgumentException("Lifecycle move-line snapshot requires quant and positive quantity");
            }
        }
    }
}
