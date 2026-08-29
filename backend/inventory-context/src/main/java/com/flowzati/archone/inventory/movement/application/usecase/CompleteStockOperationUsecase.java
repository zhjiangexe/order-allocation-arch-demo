package com.flowzati.archone.inventory.movement.application.usecase;

import com.flowzati.archone.inventory.movement.application.StockOperationComposite;
import com.flowzati.archone.inventory.movement.application.StockOperationLifecycleSnapshot;
import com.flowzati.archone.inventory.movement.application.port.StockOperationLifecyclePublisher;
import com.flowzati.archone.inventory.movement.application.repo.StockMoveStore;
import com.flowzati.archone.inventory.movement.application.repo.StockOperationStore;
import com.flowzati.archone.inventory.movement.domain.MoveState;
import com.flowzati.archone.inventory.movement.domain.StockOperationLifecycleAction;
import com.flowzati.archone.inventory.movement.domain.StockOperationState;
import com.flowzati.archone.inventory.movement.domain.aggregate.StockMove;
import com.flowzati.archone.inventory.movement.domain.aggregate.StockOperation;
import com.flowzati.archone.inventory.position.application.store.StockQuantStore;
import com.flowzati.archone.inventory.position.domain.StockQuant;
import com.flowzati.archone.inventory.reservation.application.MoveQuantAllocationSet;
import com.flowzati.archone.inventory.reservation.application.repo.StockMoveLineStore;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Completes physical outbound movement while retaining move lines as execution evidence. */
@Service
public class CompleteStockOperationUsecase {

    private final StockOperationStore stockOperationStore;
    private final StockMoveStore stockMoveStore;
    private final StockMoveLineStore stockMoveLineStore;
    private final StockQuantStore stockQuantStore;
    private final StockOperationLifecyclePublisher lifecyclePublisher;

    public CompleteStockOperationUsecase(
            StockOperationStore stockOperationStore,
            StockMoveStore stockMoveStore,
            StockMoveLineStore stockMoveLineStore,
            StockQuantStore stockQuantStore,
            StockOperationLifecyclePublisher lifecyclePublisher) {
        this.stockOperationStore = stockOperationStore;
        this.stockMoveStore = stockMoveStore;
        this.stockMoveLineStore = stockMoveLineStore;
        this.stockQuantStore = stockQuantStore;
        this.lifecyclePublisher = lifecyclePublisher;
    }

    /** @return {@code true} for the state change and {@code false} for a completed replay. */
    @Transactional
    public boolean execute(UUID stockOperationId, Instant occurredAt) {
        if (stockOperationId == null || occurredAt == null) {
            throw new IllegalArgumentException("Stock operation identity and completion time are required");
        }
        StockOperation operation = stockOperationStore
                .lockById(stockOperationId)
                .orElseThrow(() -> new IllegalStateException("Stock operation no longer exists: " + stockOperationId));
        List<StockMove> moves = stockMoveStore.lockByStockOperationIdInIdOrder(stockOperationId);
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
        StockOperationLifecycleSnapshot snapshot = operationComposite.lifecycleSnapshot(occurredAt);

        MoveQuantAllocationSet allocationSet = MoveQuantAllocationSet.fromMoveLines(operationComposite.lines());
        List<StockQuant> stockQuants = allocationSet.validateAndOrder(
                stockQuantStore.lockByIds(allocationSet.stockQuantIds()), operationComposite, null, false);

        stockQuants.forEach(stockQuant -> {
            stockQuant.consume(allocationSet.quantityForStockQuant(stockQuant.getId()));
            stockQuantStore.save(stockQuant);
        });
        operationComposite.moves().forEach(move -> move.complete(occurredAt));
        stockMoveStore.saveAll(operationComposite.moves());
        operation.complete();
        stockOperationStore.save(operation);
        lifecyclePublisher.publish(snapshot, StockOperationLifecycleAction.COMPLETED);
        return true;
    }
}
