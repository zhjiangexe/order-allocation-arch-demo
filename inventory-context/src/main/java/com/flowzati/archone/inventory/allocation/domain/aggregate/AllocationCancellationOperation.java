package com.flowzati.archone.inventory.allocation.domain.aggregate;

import com.flowzati.archone.inventory.allocation.domain.type.AllocationCancellationState;
import java.time.Instant;
import java.util.UUID;

/** 一次可重送的 allocation cancellation saga checkpoint。 */
public final class AllocationCancellationOperation {

    private final UUID allocationDemandId;
    private final UUID operationId;
    private final Instant startedAt;
    private Instant updatedAt;
    private AllocationCancellationState state;
    private final Long version;

    private AllocationCancellationOperation(
            UUID allocationDemandId,
            UUID operationId,
            Instant startedAt,
            Instant updatedAt,
            AllocationCancellationState state,
            Long version) {
        if (allocationDemandId == null
                || operationId == null
                || startedAt == null
                || updatedAt == null
                || state == null) {
            throw new IllegalArgumentException("Cancellation operation requires identity, time and state");
        }
        if (updatedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("Cancellation operation update cannot precede its start");
        }
        this.allocationDemandId = allocationDemandId;
        this.operationId = operationId;
        this.startedAt = startedAt;
        this.updatedAt = updatedAt;
        this.state = state;
        this.version = version;
    }

    public static AllocationCancellationOperation start(UUID allocationDemandId, UUID operationId, Instant startedAt) {
        return new AllocationCancellationOperation(
                allocationDemandId, operationId, startedAt, startedAt, AllocationCancellationState.STARTED, null);
    }

    public static AllocationCancellationOperation rehydrate(
            UUID allocationDemandId,
            UUID operationId,
            Instant startedAt,
            Instant updatedAt,
            AllocationCancellationState state,
            Long version) {
        return new AllocationCancellationOperation(
                allocationDemandId, operationId, startedAt, updatedAt, state, version);
    }

    public void rejectExternally(Instant decidedAt) {
        if (state == AllocationCancellationState.EXTERNAL_REJECTED) {
            return;
        }
        if (state != AllocationCancellationState.STARTED) {
            throw new IllegalStateException("Only a started cancellation can be externally rejected");
        }
        state = AllocationCancellationState.EXTERNAL_REJECTED;
        updatedAt = requireTransitionTime(decidedAt);
    }

    public void confirmExternally(Instant decidedAt) {
        if (state == AllocationCancellationState.EXTERNAL_CONFIRMED || state == AllocationCancellationState.COMPLETED) {
            return;
        }
        if (state != AllocationCancellationState.STARTED) {
            throw new IllegalStateException("A rejected cancellation decision cannot be reevaluated");
        }
        state = AllocationCancellationState.EXTERNAL_CONFIRMED;
        updatedAt = requireTransitionTime(decidedAt);
    }

    public void completeLocally(Instant completedAt) {
        if (state == AllocationCancellationState.COMPLETED) {
            return;
        }
        if (state != AllocationCancellationState.EXTERNAL_CONFIRMED) {
            throw new IllegalStateException("Local cancellation requires durable external confirmation");
        }
        state = AllocationCancellationState.COMPLETED;
        updatedAt = requireTransitionTime(completedAt);
    }

    private Instant requireTransitionTime(Instant transitionAt) {
        if (transitionAt == null || transitionAt.isBefore(updatedAt)) {
            throw new IllegalArgumentException("Cancellation transition time must be monotonic");
        }
        return transitionAt;
    }

    public UUID allocationDemandId() {
        return allocationDemandId;
    }

    public UUID operationId() {
        return operationId;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public AllocationCancellationState state() {
        return state;
    }

    public Long version() {
        return version;
    }
}
