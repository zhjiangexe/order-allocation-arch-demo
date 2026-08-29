package com.flowzati.archone.inventory.movement.application.usecase;

import com.flowzati.archone.inventory.movement.application.command.CompleteSourceStockMovementsCommand;
import com.flowzati.archone.inventory.movement.application.repo.StockMoveStore;
import com.flowzati.archone.inventory.movement.application.repo.StockOperationStore;
import com.flowzati.archone.inventory.movement.domain.aggregate.StockMove;
import com.flowzati.archone.inventory.movement.domain.aggregate.StockOperation;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Validates source trace at the boundary, then completes only the resolved operation target. */
@Service
public class CompleteSourceStockMovementsUsecase {

    private final StockMoveStore stockMoveStore;
    private final StockOperationStore stockOperationStore;
    private final CompleteStockOperationUsecase completeStockOperation;

    public CompleteSourceStockMovementsUsecase(
            StockMoveStore stockMoveStore,
            StockOperationStore stockOperationStore,
            CompleteStockOperationUsecase completeStockOperation) {
        this.stockMoveStore = stockMoveStore;
        this.stockOperationStore = stockOperationStore;
        this.completeStockOperation = completeStockOperation;
    }

    public SourceStockMovementsCompletionResult execute(CompleteSourceStockMovementsCommand command) {
        List<StockMove> requestedMoves = stockMoveStore.findByIds(command.moveIds());
        if (requestedMoves.size() != command.moveIds().size()) {
            throw new IllegalArgumentException("One or more completed movement identities do not exist");
        }
        Set<UUID> stockOperationIds = new HashSet<>();
        requestedMoves.forEach(move -> stockOperationIds.add(move.getStockOperationId()));
        if (stockOperationIds.size() != 1 || stockOperationIds.contains(null)) {
            throw new IllegalArgumentException("Completed movements must belong to one canonical operation");
        }
        UUID stockOperationId = stockOperationIds.iterator().next();
        StockOperation operation = stockOperationStore
                .findById(stockOperationId)
                .orElseThrow(() -> new IllegalStateException("Stock operation no longer exists: " + stockOperationId));
        if (!command.source().equals(operation.source())) {
            throw new IllegalArgumentException("Completion source does not match the movement operation");
        }
        Set<UUID> completeGroupIds = stockMoveStore.findOrderedByStockOperationId(stockOperationId).stream()
                .map(StockMove::getId)
                .collect(java.util.stream.Collectors.toSet());
        if (!completeGroupIds.equals(new HashSet<>(command.moveIds()))) {
            throw new IllegalArgumentException("Completion must identify every movement in the operation");
        }
        return new SourceStockMovementsCompletionResult(
                stockOperationId, completeStockOperation.execute(stockOperationId, command.completedAt()));
    }
}
