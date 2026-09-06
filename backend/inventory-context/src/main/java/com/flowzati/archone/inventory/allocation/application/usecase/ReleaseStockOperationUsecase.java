package com.flowzati.archone.inventory.allocation.application.usecase;

import com.flowzati.archone.inventory.allocation.application.state.MoveQuantAllocationSet;
import com.flowzati.archone.inventory.allocation.application.store.StockMoveLineStore;
import com.flowzati.archone.inventory.balance.application.store.StockQuantStore;
import com.flowzati.archone.inventory.balance.domain.aggregate.StockQuant;
import com.flowzati.archone.inventory.movement.application.event.StockOperationLifecycleChanged;
import com.flowzati.archone.inventory.movement.application.event.StockOperationLifecycleSnapshot;
import com.flowzati.archone.inventory.movement.application.port.StockOperationLifecycleChangedPublisher;
import com.flowzati.archone.inventory.movement.application.state.StockOperationComposite;
import com.flowzati.archone.inventory.movement.application.store.StockMoveStore;
import com.flowzati.archone.inventory.movement.application.store.StockOperationStore;
import com.flowzati.archone.inventory.movement.domain.aggregate.StockMove;
import com.flowzati.archone.inventory.movement.domain.aggregate.StockOperation;
import com.flowzati.archone.inventory.movement.domain.valueobject.MoveState;
import com.flowzati.archone.inventory.movement.domain.valueobject.StockOperationState;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Releases current reservation detail while preserving the canonical operation and move identities. */
@Service
public class ReleaseStockOperationUsecase {

    private final StockOperationStore stockOperationStore;
    private final StockMoveStore stockMoveStore;
    private final StockMoveLineStore stockMoveLineStore;
    private final StockQuantStore stockQuantStore;
    private final StockOperationLifecycleChangedPublisher lifecyclePublisher;

    public ReleaseStockOperationUsecase(
            StockOperationStore stockOperationStore,
            StockMoveStore stockMoveStore,
            StockMoveLineStore stockMoveLineStore,
            StockQuantStore stockQuantStore,
            StockOperationLifecycleChangedPublisher lifecyclePublisher) {
        this.stockOperationStore = stockOperationStore;
        this.stockMoveStore = stockMoveStore;
        this.stockMoveLineStore = stockMoveLineStore;
        this.stockQuantStore = stockQuantStore;
        this.lifecyclePublisher = lifecyclePublisher;
    }

    /**
     * @return {@code true} when this call releases an assigned group; {@code false} when the group is
     *     already confirmed and holds no reservation.
     */
    @Transactional
    public boolean execute(UUID stockOperationId, Instant occurredAt) {
        Optional<StockOperationLifecycleSnapshot> released = releaseForCancellation(stockOperationId, occurredAt);
        released.ifPresent(snapshot -> lifecyclePublisher.publish(StockOperationLifecycleChanged.released(snapshot)));
        return released.isPresent();
    }

    /** Releases stock for cancellation while letting the caller publish one cancellation before-image. */
    @Transactional
    public Optional<StockOperationLifecycleSnapshot> releaseForCancellation(UUID stockOperationId, Instant occurredAt) {
        if (stockOperationId == null || occurredAt == null) {
            throw new IllegalArgumentException("Stock operation identity and release time are required");
        }
        StockOperation operation = stockOperationStore
                .lockById(stockOperationId)
                .orElseThrow(() -> new IllegalStateException("Stock operation no longer exists: " + stockOperationId));
        List<StockMove> moves = stockMoveStore.lockByStockOperationIdInIdOrder(stockOperationId);
        List<UUID> moveIds = moves.stream().map(StockMove::getId).toList();
        StockOperationComposite operationComposite =
                StockOperationComposite.of(operation, moves, stockMoveLineStore.findByMoveIds(moveIds));

        if (operation.state() == StockOperationState.CONFIRMED) {
            operationComposite.requireHomogeneous(
                    StockOperationState.CONFIRMED,
                    MoveState.CONFIRMED,
                    "Confirmed stock operation requires every stock move to be confirmed");
            operationComposite.requireNoMoveLines("Confirmed stock operation must not retain move lines");
            return Optional.empty();
        }
        operationComposite.requireHomogeneous(
                StockOperationState.ASSIGNED,
                MoveState.ASSIGNED,
                "Only an assigned stock operation can release stock; stock-move state must be homogeneous");
        operationComposite.requireExactCoverage("Assigned move lines must exactly cover every stock move");
        StockOperationLifecycleSnapshot snapshot = operationComposite.lifecycleSnapshot(occurredAt);

        MoveQuantAllocationSet allocationSet = MoveQuantAllocationSet.fromMoveLines(operationComposite.lines());
        List<StockQuant> stockQuants = allocationSet.validateAndOrder(
                stockQuantStore.lockByIds(allocationSet.stockQuantIds()), operationComposite, null, false);

        stockQuants.forEach(stockQuant -> {
            stockQuant.release(allocationSet.quantityForStockQuant(stockQuant.getId()));
            stockQuantStore.save(stockQuant);
        });
        stockMoveLineStore.deleteByMoveIds(operationComposite.moveIds());
        operationComposite.moves().forEach(StockMove::unassign);
        stockMoveStore.saveAll(operationComposite.moves());
        operation.unassign();
        stockOperationStore.save(operation);
        return Optional.of(snapshot);
    }
}
