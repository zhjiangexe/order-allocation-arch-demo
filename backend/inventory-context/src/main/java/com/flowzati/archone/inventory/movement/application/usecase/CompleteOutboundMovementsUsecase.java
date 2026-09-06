package com.flowzati.archone.inventory.movement.application.usecase;

import com.flowzati.archone.inventory.allocation.application.state.MoveQuantAllocationSet;
import com.flowzati.archone.inventory.allocation.application.store.StockMoveLineStore;
import com.flowzati.archone.inventory.balance.application.store.StockQuantStore;
import com.flowzati.archone.inventory.balance.domain.aggregate.StockQuant;
import com.flowzati.archone.inventory.movement.application.event.StockOperationCompleted;
import com.flowzati.archone.inventory.movement.application.event.StockOperationLifecycleSnapshot;
import com.flowzati.archone.inventory.movement.application.invocation.CompleteOutboundMovementsCommand;
import com.flowzati.archone.inventory.movement.application.port.StockOperationCompletedPublisher;
import com.flowzati.archone.inventory.movement.application.state.StockOperationComposite;
import com.flowzati.archone.inventory.movement.application.store.StockMoveStore;
import com.flowzati.archone.inventory.movement.application.store.StockOperationStore;
import com.flowzati.archone.inventory.movement.domain.aggregate.StockMove;
import com.flowzati.archone.inventory.movement.domain.aggregate.StockOperation;
import com.flowzati.archone.inventory.movement.domain.valueobject.MoveState;
import com.flowzati.archone.inventory.movement.domain.valueobject.StockOperationSource;
import com.flowzati.archone.inventory.movement.domain.valueobject.StockOperationState;
import jakarta.transaction.Transactional;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Completes one canonical outbound operation and publishes its single completion fact atomically. */
@Service
public class CompleteOutboundMovementsUsecase {

    private final StockOperationStore stockOperationStore;
    private final StockMoveStore stockMoveStore;
    private final StockMoveLineStore stockMoveLineStore;
    private final StockQuantStore stockQuantStore;
    private final StockOperationCompletedPublisher stockOperationCompletedPublisher;

    public CompleteOutboundMovementsUsecase(
            StockOperationStore stockOperationStore,
            StockMoveStore stockMoveStore,
            StockMoveLineStore stockMoveLineStore,
            StockQuantStore stockQuantStore,
            StockOperationCompletedPublisher stockOperationCompletedPublisher) {
        this.stockOperationStore = stockOperationStore;
        this.stockMoveStore = stockMoveStore;
        this.stockMoveLineStore = stockMoveLineStore;
        this.stockQuantStore = stockQuantStore;
        this.stockOperationCompletedPublisher = stockOperationCompletedPublisher;
    }

    /** @return {@code true} for the state change and {@code false} for a completed replay. */
    @Transactional
    public boolean execute(CompleteOutboundMovementsCommand command) {
        UUID stockOperationId = resolveStockOperationId(command);
        StockOperation operation = lockOperation(stockOperationId);
        List<StockMove> moves = stockMoveStore.lockByStockOperationIdInIdOrder(stockOperationId);
        validateCompletionProof(command, operation, moves);

        StockOperationComposite operationComposite = StockOperationComposite.of(
                operation,
                moves,
                stockMoveLineStore.findByMoveIds(
                        moves.stream().map(StockMove::getId).toList()));
        operationComposite.requireExactCoverage("Execution move lines must exactly cover every stock move");
        if (operation.state() == StockOperationState.DONE) {
            operationComposite.requireHomogeneous(
                    StockOperationState.DONE,
                    MoveState.DONE,
                    "Completed stock operation requires every stock move to be done");
            return false;
        }
        operationComposite.requireHomogeneous(
                StockOperationState.ASSIGNED,
                MoveState.ASSIGNED,
                "Only a consistently assigned stock operation can be completed");

        StockOperationLifecycleSnapshot snapshot = operationComposite.lifecycleSnapshot(command.completedAt());
        consumeReservedStock(operationComposite);
        operationComposite.moves().forEach(move -> move.complete(command.completedAt()));
        stockMoveStore.saveAll(operationComposite.moves());
        operation.complete();
        stockOperationStore.save(operation);

        // Completion is one Inventory fact. Its adapter derives the audit and fulfillment contracts atomically.
        stockOperationCompletedPublisher.publish(
                new StockOperationCompleted(snapshot, command.orderId(), command.shipmentId()));
        return true;
    }

    private UUID resolveStockOperationId(CompleteOutboundMovementsCommand command) {
        List<StockMove> requestedMoves = stockMoveStore.findByIds(command.movementIds());
        if (requestedMoves.size() != command.movementIds().size()) {
            throw new IllegalArgumentException("One or more completed movement identities do not exist");
        }
        Set<UUID> stockOperationIds = new HashSet<>();
        requestedMoves.forEach(move -> stockOperationIds.add(move.getStockOperationId()));
        if (stockOperationIds.size() != 1 || stockOperationIds.contains(null)) {
            throw new IllegalArgumentException("Completed movements must belong to one canonical operation");
        }
        UUID stockOperationId = stockOperationIds.iterator().next();
        if (command.expectedStockOperationId() != null
                && !command.expectedStockOperationId().equals(stockOperationId)) {
            throw new IllegalArgumentException("Handover stock operation does not match the completed movement group");
        }
        return stockOperationId;
    }

    private StockOperation lockOperation(UUID stockOperationId) {
        return stockOperationStore
                .lockById(stockOperationId)
                .orElseThrow(() -> new IllegalStateException("Stock operation no longer exists: " + stockOperationId));
    }

    private void validateCompletionProof(
            CompleteOutboundMovementsCommand command, StockOperation operation, List<StockMove> moves) {
        StockOperationSource source =
                StockOperationSource.primaryOrder(command.orderId().toString());
        if (!source.equals(operation.source())) {
            throw new IllegalArgumentException("Completion source does not match the movement operation");
        }
        Set<UUID> completeGroupIds = moves.stream().map(StockMove::getId).collect(java.util.stream.Collectors.toSet());
        if (!completeGroupIds.equals(new HashSet<>(command.movementIds()))) {
            throw new IllegalArgumentException("Completion must identify every movement in the operation");
        }
    }

    private void consumeReservedStock(StockOperationComposite operationComposite) {
        MoveQuantAllocationSet allocationSet = MoveQuantAllocationSet.fromMoveLines(operationComposite.lines());
        List<StockQuant> stockQuants = allocationSet.validateAndOrder(
                stockQuantStore.lockByIds(allocationSet.stockQuantIds()), operationComposite, null, false);
        stockQuants.forEach(stockQuant -> {
            stockQuant.consume(allocationSet.quantityForStockQuant(stockQuant.getId()));
            stockQuantStore.save(stockQuant);
        });
    }
}
