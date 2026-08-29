package com.flowzati.archone.inventory.movement.application.service;

import com.flowzati.archone.inventory.movement.application.StockOperationComposite;
import com.flowzati.archone.inventory.movement.application.StockOperationLifecycleSnapshot;
import com.flowzati.archone.inventory.movement.application.port.StockOperationLifecyclePublisher;
import com.flowzati.archone.inventory.movement.application.port.WarehouseExecutionCancellationCoordinator.Decision;
import com.flowzati.archone.inventory.movement.application.port.WarehouseExecutionCancellationCoordinator.Target;
import com.flowzati.archone.inventory.movement.application.repo.StockMoveStore;
import com.flowzati.archone.inventory.movement.application.repo.StockOperationCancellationStore;
import com.flowzati.archone.inventory.movement.application.repo.StockOperationStore;
import com.flowzati.archone.inventory.movement.domain.MoveState;
import com.flowzati.archone.inventory.movement.domain.StockOperationCancellation;
import com.flowzati.archone.inventory.movement.domain.StockOperationCancellationState;
import com.flowzati.archone.inventory.movement.domain.StockOperationCancellationStatus;
import com.flowzati.archone.inventory.movement.domain.StockOperationLifecycleAction;
import com.flowzati.archone.inventory.movement.domain.StockOperationState;
import com.flowzati.archone.inventory.movement.domain.aggregate.StockMove;
import com.flowzati.archone.inventory.movement.domain.aggregate.StockOperation;
import com.flowzati.archone.inventory.reservation.application.repo.StockMoveLineStore;
import com.flowzati.archone.inventory.reservation.application.usecase.ReleaseStockOperationUsecase;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Short local transactions before and after warehouse cancellation coordination. */
@Component
public class StockOperationCancellationTransactions {

    private final StockOperationStore stockOperationStore;
    private final StockMoveStore stockMoveStore;
    private final StockMoveLineStore stockMoveLineStore;
    private final StockOperationCancellationStore stockOperationCancellationStore;
    private final ReleaseStockOperationUsecase releaseStockOperation;
    private final StockOperationLifecyclePublisher lifecyclePublisher;

    public StockOperationCancellationTransactions(
            StockOperationStore stockOperationStore,
            StockMoveStore stockMoveStore,
            StockMoveLineStore stockMoveLineStore,
            StockOperationCancellationStore stockOperationCancellationStore,
            ReleaseStockOperationUsecase releaseStockOperation,
            StockOperationLifecyclePublisher lifecyclePublisher) {
        this.stockOperationStore = stockOperationStore;
        this.stockMoveStore = stockMoveStore;
        this.stockMoveLineStore = stockMoveLineStore;
        this.stockOperationCancellationStore = stockOperationCancellationStore;
        this.releaseStockOperation = releaseStockOperation;
        this.lifecyclePublisher = lifecyclePublisher;
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public Preparation prepare(UUID stockOperationId, UUID cancellationOperationId, Instant now) {
        StockOperationComposite operationComposite = loadOperationCompositeForUpdate(stockOperationId);
        StockOperation stockOperation = operationComposite.operation();

        if (stockOperation.state() == StockOperationState.CANCELLED) {
            operationComposite.requireHomogeneous(
                    StockOperationState.CANCELLED,
                    MoveState.CANCELLED,
                    "Cancelled stock operation requires every stock move to be cancelled");
            operationComposite.requireNoMoveLines("Cancelled stock operation must not retain move lines");
            return new Preparation.Terminal(StockOperationCancellationStatus.COMPLETED);
        }
        if (stockOperation.state() == StockOperationState.DONE) {
            operationComposite.requireHomogeneous(
                    StockOperationState.DONE,
                    MoveState.DONE,
                    "Completed stock operation requires every stock move to be done");
            return new Preparation.Terminal(StockOperationCancellationStatus.NOT_CANCELLABLE);
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
            return new Preparation.Terminal(StockOperationCancellationStatus.COMPLETED);
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
        return new Preparation.Continue(new Checkpoint(new Target(stockOperationId), cancellation.state()));
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public Checkpoint recordExternalDecision(
            UUID stockOperationId, UUID cancellationOperationId, Decision decision, Instant now) {
        StockOperationCancellation cancellation = stockOperationCancellationStore
                .find(stockOperationId, cancellationOperationId)
                .orElseThrow(() -> new IllegalStateException("Stock operation cancellation was not started"));
        if (cancellation.state() == StockOperationCancellationState.STARTED) {
            if (decision == Decision.CONFIRMED) {
                cancellation.confirmExternally(now);
            } else {
                cancellation.rejectExternally(now);
            }
            cancellation = stockOperationCancellationStore.save(cancellation);
        }
        return new Checkpoint(new Target(stockOperationId), cancellation.state());
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
        lifecyclePublisher.publish(snapshot, StockOperationLifecycleAction.CANCELLED);
    }

    public sealed interface Preparation {

        record Terminal(StockOperationCancellationStatus status) implements Preparation {

            public Terminal {
                if (status == null) {
                    throw new IllegalArgumentException("Cancellation status is required");
                }
            }
        }

        record Continue(Checkpoint checkpoint) implements Preparation {

            public Continue {
                if (checkpoint == null) {
                    throw new IllegalArgumentException("Cancellation checkpoint is required");
                }
            }
        }
    }

    public record Checkpoint(Target target, StockOperationCancellationState state) {

        public Checkpoint {
            if (target == null || state == null) {
                throw new IllegalArgumentException("Cancellation target and state are required");
            }
        }
    }
}
