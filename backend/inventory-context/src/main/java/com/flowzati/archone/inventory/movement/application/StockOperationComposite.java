package com.flowzati.archone.inventory.movement.application;

import com.flowzati.archone.inventory.allocation.domain.ProposedMoveLine;
import com.flowzati.archone.inventory.movement.domain.MoveState;
import com.flowzati.archone.inventory.movement.domain.StockOperationState;
import com.flowzati.archone.inventory.movement.domain.aggregate.StockMove;
import com.flowzati.archone.inventory.movement.domain.aggregate.StockOperation;
import com.flowzati.archone.inventory.reservation.domain.StockMoveLine;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Complete StockOperation, StockMove and StockMoveLine composition used by one allocation transaction.
 *
 * <p>This ephemeral application model neither acquires nor owns database locks. It has no identity, repository or
 * lifecycle of its own, is not a persisted aggregate, and never becomes reservation truth.
 */
public final class StockOperationComposite {

    private final StockOperation operation;
    private final List<StockMove> moves;
    private final List<StockMoveLine> lines;
    private final Map<UUID, StockMove> movesById;

    private StockOperationComposite(StockOperation operation, List<StockMove> moves, List<StockMoveLine> lines) {
        if (operation == null || moves == null || lines == null) {
            throw new IllegalArgumentException("Stock operation composite facts are required");
        }
        if (moves.isEmpty()) {
            throw new IllegalStateException("Stock operation requires its complete stock-move set");
        }

        List<StockMove> orderedMoves =
                moves.stream().sorted(Comparator.comparing(StockMove::getId)).toList();
        LinkedHashMap<UUID, StockMove> indexedMoves = new LinkedHashMap<>();
        for (StockMove move : orderedMoves) {
            if (!operation.id().equals(move.getStockOperationId()) || indexedMoves.put(move.getId(), move) != null) {
                throw new IllegalStateException("Stock operation requires its complete stock-move set");
            }
        }

        this.operation = operation;
        this.moves = orderedMoves;
        this.lines = lines.stream()
                .sorted(Comparator.comparing(StockMoveLine::moveId)
                        .thenComparing(StockMoveLine::stockQuantId)
                        .thenComparing(StockMoveLine::id))
                .toList();
        this.movesById = Map.copyOf(indexedMoves);
    }

    public static StockOperationComposite of(
            StockOperation operation, List<StockMove> moves, List<StockMoveLine> lines) {
        return new StockOperationComposite(operation, List.copyOf(moves), List.copyOf(lines));
    }

    public StockOperation operation() {
        return operation;
    }

    public List<StockMove> moves() {
        return moves;
    }

    public List<StockMoveLine> lines() {
        return lines;
    }

    public List<UUID> moveIds() {
        return moves.stream().map(StockMove::getId).toList();
    }

    public StockMove move(UUID moveId) {
        StockMove move = movesById.get(moveId);
        if (move == null) {
            throw new IllegalStateException("Move line belongs to a foreign stock move");
        }
        return move;
    }

    public Map<UUID, StockMove> movesById() {
        return movesById;
    }

    public void requireHomogeneous(
            StockOperationState expectedOperationState, MoveState expectedMoveState, String message) {
        if (operation.state() != expectedOperationState
                || moves.stream().anyMatch(move -> move.getState() != expectedMoveState)) {
            throw new IllegalStateException(message);
        }
    }

    public void requireNoMoveLines(String message) {
        if (!lines.isEmpty()) {
            throw new IllegalStateException(message);
        }
    }

    public void requireExactCoverage(String message) {
        Map<UUID, Integer> required = new LinkedHashMap<>();
        moves.forEach(move -> required.put(move.getId(), move.getDemandQuantity()));
        Map<UUID, Integer> covered = new LinkedHashMap<>();
        Set<MoveQuantKey> uniqueDetails = new HashSet<>();
        for (StockMoveLine line : lines) {
            if (!required.containsKey(line.moveId())
                    || !uniqueDetails.add(new MoveQuantKey(line.moveId(), line.stockQuantId()))) {
                throw new IllegalStateException(message);
            }
            covered.merge(line.moveId(), line.quantity(), Math::addExact);
        }
        if (!required.equals(covered)) {
            throw new IllegalStateException(message);
        }
    }

    public void requireExactProposalCoverage(Collection<ProposedMoveLine> proposedMoveLines, String message) {
        Map<UUID, Integer> required = new LinkedHashMap<>();
        moves.forEach(move -> required.put(move.getId(), move.getDemandQuantity()));
        Map<UUID, Integer> covered = new LinkedHashMap<>();
        Set<MoveQuantKey> uniqueDetails = new HashSet<>();
        for (ProposedMoveLine proposedMoveLine : proposedMoveLines) {
            if (!required.containsKey(proposedMoveLine.moveId())
                    || !uniqueDetails.add(
                            new MoveQuantKey(proposedMoveLine.moveId(), proposedMoveLine.stockQuantId()))) {
                throw new IllegalStateException(message);
            }
            covered.merge(proposedMoveLine.moveId(), proposedMoveLine.quantity(), Math::addExact);
        }
        if (!required.equals(covered)) {
            throw new IllegalStateException(message);
        }
    }

    public StockOperationLifecycleSnapshot lifecycleSnapshot(Instant occurredAt) {
        return StockOperationLifecycleSnapshot.capture(operation, moves, lines, occurredAt);
    }

    private record MoveQuantKey(UUID moveId, UUID stockQuantId) {}
}
