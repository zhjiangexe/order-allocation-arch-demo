package com.flowzati.archone.inventory.movement.domain.aggregate;

import com.flowzati.archone.inventory.movement.domain.valueobject.StockOperationCancellationState;
import java.time.Instant;
import java.util.UUID;

/** Durable checkpoint for one idempotent warehouse-execution cancellation of a stock operation. */
public final class StockOperationCancellation {

    private final UUID stockOperationId;
    private final UUID cancellationOperationId;
    private final Instant startedAt;
    private Instant updatedAt;
    private StockOperationCancellationState state;
    private final Long version;

    private StockOperationCancellation(
            UUID stockOperationId,
            UUID cancellationOperationId,
            Instant startedAt,
            Instant updatedAt,
            StockOperationCancellationState state,
            Long version) {
        if (stockOperationId == null
                || cancellationOperationId == null
                || startedAt == null
                || updatedAt == null
                || state == null) {
            throw new IllegalArgumentException("Stock operation cancellation requires identity, time and state");
        }
        if (updatedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("Stock operation cancellation update cannot precede its start");
        }
        this.stockOperationId = stockOperationId;
        this.cancellationOperationId = cancellationOperationId;
        this.startedAt = startedAt;
        this.updatedAt = updatedAt;
        this.state = state;
        this.version = version;
    }

    public static StockOperationCancellation start(
            UUID stockOperationId, UUID cancellationOperationId, Instant startedAt) {
        return new StockOperationCancellation(
                stockOperationId,
                cancellationOperationId,
                startedAt,
                startedAt,
                StockOperationCancellationState.STARTED,
                null);
    }

    public static StockOperationCancellation rehydrate(
            UUID stockOperationId,
            UUID cancellationOperationId,
            Instant startedAt,
            Instant updatedAt,
            StockOperationCancellationState state,
            Long version) {
        return new StockOperationCancellation(
                stockOperationId, cancellationOperationId, startedAt, updatedAt, state, version);
    }

    public void rejectExternally(Instant decidedAt) {
        if (state == StockOperationCancellationState.EXTERNAL_REJECTED) {
            return;
        }
        if (state != StockOperationCancellationState.STARTED) {
            throw new IllegalStateException("Only a started operation cancellation can be rejected");
        }
        state = StockOperationCancellationState.EXTERNAL_REJECTED;
        updatedAt = requireTransitionTime(decidedAt);
    }

    public void confirmExternally(Instant decidedAt) {
        if (state == StockOperationCancellationState.EXTERNAL_CONFIRMED
                || state == StockOperationCancellationState.COMPLETED) {
            return;
        }
        if (state != StockOperationCancellationState.STARTED) {
            throw new IllegalStateException("A rejected operation cancellation cannot be reevaluated");
        }
        state = StockOperationCancellationState.EXTERNAL_CONFIRMED;
        updatedAt = requireTransitionTime(decidedAt);
    }

    public void completeLocally(Instant completedAt) {
        if (state == StockOperationCancellationState.COMPLETED) {
            return;
        }
        if (state != StockOperationCancellationState.EXTERNAL_CONFIRMED) {
            throw new IllegalStateException("Local cancellation requires durable warehouse confirmation");
        }
        state = StockOperationCancellationState.COMPLETED;
        updatedAt = requireTransitionTime(completedAt);
    }

    private Instant requireTransitionTime(Instant transitionAt) {
        if (transitionAt == null || transitionAt.isBefore(updatedAt)) {
            throw new IllegalArgumentException("Stock operation cancellation transition time must be monotonic");
        }
        return transitionAt;
    }

    public UUID stockOperationId() {
        return stockOperationId;
    }

    public UUID cancellationOperationId() {
        return cancellationOperationId;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public StockOperationCancellationState state() {
        return state;
    }

    public Long version() {
        return version;
    }
}
