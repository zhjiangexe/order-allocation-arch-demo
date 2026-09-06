package com.flowzati.archone.inventory.movement.application.service;

import com.flowzati.archone.inventory.allocation.application.store.StockMoveLineStore;
import com.flowzati.archone.inventory.allocation.application.usecase.ReleaseStockOperationUsecase;
import com.flowzati.archone.inventory.movement.application.event.StockOperationLifecycleChanged;
import com.flowzati.archone.inventory.movement.application.event.StockOperationLifecycleSnapshot;
import com.flowzati.archone.inventory.movement.application.port.StockOperationLifecycleChangedPublisher;
import com.flowzati.archone.inventory.movement.application.result.StockOperationCancellationCheckpoint;
import com.flowzati.archone.inventory.movement.application.result.StockOperationCancellationPreparation;
import com.flowzati.archone.inventory.movement.application.result.StockOperationCancellationStatus;
import com.flowzati.archone.inventory.movement.application.state.StockOperationComposite;
import com.flowzati.archone.inventory.movement.application.store.StockMoveStore;
import com.flowzati.archone.inventory.movement.application.store.StockOperationCancellationStore;
import com.flowzati.archone.inventory.movement.application.store.StockOperationStore;
import com.flowzati.archone.inventory.movement.domain.aggregate.StockMove;
import com.flowzati.archone.inventory.movement.domain.aggregate.StockOperation;
import com.flowzati.archone.inventory.movement.domain.aggregate.StockOperationCancellation;
import com.flowzati.archone.inventory.movement.domain.valueobject.MoveState;
import com.flowzati.archone.inventory.movement.domain.valueobject.StockOperationCancellationState;
import com.flowzati.archone.inventory.movement.domain.valueobject.StockOperationState;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Durable local transactions for an externally confirmed stock-operation cancellation. */
@Component
public class StockOperationCancellationTransactions {

    private final StockOperationStore stockOperationStore;
    private final StockMoveStore stockMoveStore;
    private final StockMoveLineStore stockMoveLineStore;
    private final StockOperationCancellationStore stockOperationCancellationStore;
    private final ReleaseStockOperationUsecase releaseStockOperation;
    private final StockOperationLifecycleChangedPublisher lifecyclePublisher;

    public StockOperationCancellationTransactions(
            StockOperationStore stockOperationStore,
            StockMoveStore stockMoveStore,
            StockMoveLineStore stockMoveLineStore,
            StockOperationCancellationStore stockOperationCancellationStore,
            ReleaseStockOperationUsecase releaseStockOperation,
            StockOperationLifecycleChangedPublisher lifecyclePublisher) {
        this.stockOperationStore = stockOperationStore;
        this.stockMoveStore = stockMoveStore;
        this.stockMoveLineStore = stockMoveLineStore;
        this.stockOperationCancellationStore = stockOperationCancellationStore;
        this.releaseStockOperation = releaseStockOperation;
        this.lifecyclePublisher = lifecyclePublisher;
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public StockOperationCancellationPreparation prepare(
            UUID stockOperationId, UUID cancellationOperationId, Instant now) {
        StockOperationComposite operationComposite = loadOperationCompositeForUpdate(stockOperationId);
        StockOperation stockOperation = operationComposite.operation();

        if (stockOperation.state() == StockOperationState.CANCELLED) {
            operationComposite.requireHomogeneous(
                    StockOperationState.CANCELLED,
                    MoveState.CANCELLED,
                    "Cancelled stock operation requires every stock move to be cancelled");
            operationComposite.requireNoMoveLines("Cancelled stock operation must not retain move lines");
            return new StockOperationCancellationPreparation.Terminal(StockOperationCancellationStatus.COMPLETED);
        }
        if (stockOperation.state() == StockOperationState.DONE) {
            operationComposite.requireHomogeneous(
                    StockOperationState.DONE,
                    MoveState.DONE,
                    "Completed stock operation requires every stock move to be done");
            return new StockOperationCancellationPreparation.Terminal(StockOperationCancellationStatus.NOT_CANCELLABLE);
        }
        if (stockOperation.state() == StockOperationState.CONFIRMED) {
            operationComposite.requireHomogeneous(
                    StockOperationState.CONFIRMED,
                    MoveState.CONFIRMED,
                    "Confirmed stock operation requires every stock move to be confirmed");
            operationComposite.requireNoMoveLines("Confirmed stock operation must not retain move lines");
            StockOperationLifecycleSnapshot snapshot = operationComposite.lifecycleSnapshot(now);
            operationComposite.moves().forEach(StockMove::cancel);
            stockMoveStore.saveAll(operationComposite.moves());
            stockOperation.cancel();
            stockOperationStore.save(stockOperation);
            publishCancellation(snapshot);
            return new StockOperationCancellationPreparation.Terminal(StockOperationCancellationStatus.COMPLETED);
        }

        operationComposite.requireHomogeneous(
                StockOperationState.ASSIGNED,
                MoveState.ASSIGNED,
                "Assigned stock operation requires every stock move to be assigned");
        operationComposite.requireExactCoverage("Assigned operation requires exact move-line coverage");
        StockOperationCancellation cancellation = stockOperationCancellationStore
                .find(stockOperationId, cancellationOperationId)
                .orElseGet(() -> stockOperationCancellationStore.save(
                        StockOperationCancellation.start(stockOperationId, cancellationOperationId, now)));
        return new StockOperationCancellationPreparation.Continue(
                new StockOperationCancellationCheckpoint(cancellation.state()));
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public StockOperationCancellationCheckpoint confirmWarehouseCancellation(
            UUID stockOperationId, UUID cancellationOperationId, Instant now) {
        StockOperationCancellation cancellation = stockOperationCancellationStore
                .find(stockOperationId, cancellationOperationId)
                .orElseThrow(() -> new IllegalStateException("Stock operation cancellation was not started"));
        if (cancellation.state() == StockOperationCancellationState.STARTED) {
            cancellation.confirmExternally(now);
            cancellation = stockOperationCancellationStore.save(cancellation);
        }
        return new StockOperationCancellationCheckpoint(cancellation.state());
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public StockOperationCancellationStatus complete(UUID stockOperationId, UUID cancellationOperationId, Instant now) {
        StockOperationCancellation cancellation = stockOperationCancellationStore
                .find(stockOperationId, cancellationOperationId)
                .orElseThrow(() -> new IllegalStateException("Stock operation cancellation was not started"));
        if (cancellation.state() == StockOperationCancellationState.COMPLETED) {
            return StockOperationCancellationStatus.COMPLETED;
        }
        if (cancellation.state() == StockOperationCancellationState.EXTERNAL_REJECTED) {
            return StockOperationCancellationStatus.NOT_CANCELLABLE;
        }
        if (cancellation.state() != StockOperationCancellationState.EXTERNAL_CONFIRMED) {
            throw new IllegalStateException("Local cancellation requires durable warehouse confirmation");
        }

        StockOperationComposite operationComposite = loadOperationCompositeForUpdate(stockOperationId);
        StockOperation stockOperation = operationComposite.operation();
        StockOperationLifecycleSnapshot snapshot = null;
        if (stockOperation.state() == StockOperationState.DONE) {
            throw new IllegalStateException("A completed operation cannot be cancelled");
        }
        if (stockOperation.state() == StockOperationState.ASSIGNED) {
            snapshot = releaseStockOperation
                    .releaseForCancellation(stockOperationId, now)
                    .orElseThrow(
                            () -> new IllegalStateException("Assigned operation release produced no before-image"));
            operationComposite = loadOperationCompositeForUpdate(stockOperationId);
            stockOperation = operationComposite.operation();
        }
        operationComposite.requireHomogeneous(
                StockOperationState.CONFIRMED,
                MoveState.CONFIRMED,
                "Released stock operation requires every stock move to be confirmed");
        operationComposite.requireNoMoveLines("Released stock operation must not retain move lines");
        if (snapshot == null) {
            snapshot = operationComposite.lifecycleSnapshot(now);
        }
        operationComposite.moves().forEach(StockMove::cancel);
        stockMoveStore.saveAll(operationComposite.moves());
        stockOperation.cancel();
        stockOperationStore.save(stockOperation);

        cancellation.completeLocally(now);
        stockOperationCancellationStore.save(cancellation);
        publishCancellation(snapshot);
        return StockOperationCancellationStatus.COMPLETED;
    }

    private StockOperationComposite loadOperationCompositeForUpdate(UUID stockOperationId) {
        StockOperation operation = stockOperationStore
                .lockById(stockOperationId)
                .orElseThrow(() -> new IllegalStateException("Stock operation no longer exists: " + stockOperationId));
        List<StockMove> moves = stockMoveStore.lockByStockOperationIdInIdOrder(operation.id());
        return StockOperationComposite.of(
                operation,
                moves,
                stockMoveLineStore.findByMoveIds(
                        moves.stream().map(StockMove::getId).toList()));
    }

    private void publishCancellation(StockOperationLifecycleSnapshot snapshot) {
        lifecyclePublisher.publish(StockOperationLifecycleChanged.cancelled(snapshot));
    }
}
