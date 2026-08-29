package com.flowzati.archone.inventory.movement.domain.aggregate;

import com.flowzati.archone.inventory.movement.domain.MovementAssignmentPolicy;
import com.flowzati.archone.inventory.movement.domain.StockOperationDirection;
import com.flowzati.archone.inventory.movement.domain.StockOperationSource;
import com.flowzati.archone.inventory.movement.domain.StockOperationState;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 一組 Inventory movement operation。**共同套用 movement policy 的分組。**
 *
 * <p>沒有 SKU、沒有數量、不直接影響庫存——它只說哪些 moves 同屬一個 source unit、從哪到哪，
 * 並套用哪一種 assignment policy。數量的真相在底下的 {@link StockMove}。
 *
 * <p><b>它不是 WMS warehouse task。</b>Shipment、Wave、PickTask、operator progress 與 packing state
 * 由 WMS 擁有；Inventory 只在 assignment fact 中把 {@code stockOperationId} 當成跨邊界 reference。
 *
 * <p>來源文件身分只存在 source-neutral {@link com.flowzati.archone.inventory.movement.domain.StockOperationSource}。Inventory core 不帶
 * {@code orderId}；ORDER、TRANSFER 或 PRODUCTION 的識別解讀都停在各自的 source adapter。
 *
 * <p><b>狀態是底下 moves 的物化摘要。</b>它不是另一套獨立生命週期：建立時為
 * {@link com.flowzati.archone.inventory.movement.domain.StockOperationState#CONFIRMED}，moves 全部鎖定時進 {@link com.flowzati.archone.inventory.movement.domain.StockOperationState#ASSIGNED}，完成或
 * 取消也與 moves 在同一個 transaction 更新。物化的目的是讓作業單列表可以直接篩選與排程。
 *
 * <p>一旦狀態可寫，配貨與取消就可能同時修改同一張單，因此 operation 也必須帶 optimistic-lock
 * {@code version}，不能再依賴「只有建立時寫一次」的舊假設。
 */
public class StockOperation {

    private final UUID id;
    private final UUID stockOperationTypeId;
    private final StockOperationDirection direction;
    private final UUID ownerId;
    private final UUID fromLocationId;
    private final UUID toLocationId;
    private final StockOperationSource source;
    private final MovementAssignmentPolicy assignmentPolicy;
    private final Instant enqueuedAt;
    /** Outbound handoff scheduling facts；inbound operation 不帶。 */
    private final Instant dispatchBy;

    private final Integer releasePriority;
    private StockOperationState state;
    private final Long version;

    public StockOperation(
            UUID id,
            UUID stockOperationTypeId,
            StockOperationDirection direction,
            UUID ownerId,
            UUID fromLocationId,
            UUID toLocationId,
            StockOperationSource source,
            MovementAssignmentPolicy assignmentPolicy,
            Instant enqueuedAt,
            Instant dispatchBy,
            Integer releasePriority,
            StockOperationState state,
            Long version) {
        if (id == null || stockOperationTypeId == null || direction == null || ownerId == null) {
            throw new IllegalArgumentException("Stock operation requires an id, a type and an owner");
        }
        if (fromLocationId == null || toLocationId == null) {
            throw new IllegalArgumentException("A stock operation must say where the work runs between");
        }
        if (state == null) {
            throw new IllegalArgumentException("Stock operation state is required");
        }
        if ((source == null) != (assignmentPolicy == null) || (source == null) != (enqueuedAt == null)) {
            throw new IllegalArgumentException(
                    "Source identity, assignment policy and enqueue time must appear together");
        }
        if (direction == StockOperationDirection.INBOUND && source != null) {
            throw new IllegalArgumentException("Inbound supply operations do not use stock-assignment source identity");
        }
        if (direction != StockOperationDirection.INBOUND && source == null) {
            throw new IllegalArgumentException(
                    "A stock-consuming operation requires source identity and assignment policy");
        }
        if ((direction == StockOperationDirection.INBOUND && (dispatchBy != null || releasePriority != null))
                || (direction != StockOperationDirection.INBOUND && (dispatchBy == null || releasePriority == null))) {
            throw new IllegalArgumentException(
                    "Outbound operation requires dispatch deadline and release priority; inbound requires neither");
        }
        if (releasePriority != null && (releasePriority < 0 || releasePriority > 100)) {
            throw new IllegalArgumentException("Release priority must be between 0 and 100");
        }
        this.id = id;
        this.stockOperationTypeId = stockOperationTypeId;
        this.direction = direction;
        this.ownerId = ownerId;
        this.fromLocationId = fromLocationId;
        this.toLocationId = toLocationId;
        this.source = source;
        this.assignmentPolicy = assignmentPolicy;
        this.enqueuedAt = enqueuedAt;
        this.dispatchBy = dispatchBy;
        this.releasePriority = releasePriority;
        this.state = state;
        this.version = version;
    }

    public static StockOperation confirmedInbound(
            UUID id, UUID stockOperationTypeId, UUID ownerId, UUID fromLocationId, UUID toLocationId) {
        return new StockOperation(
                id,
                stockOperationTypeId,
                StockOperationDirection.INBOUND,
                ownerId,
                fromLocationId,
                toLocationId,
                null,
                null,
                null,
                null,
                null,
                StockOperationState.CONFIRMED,
                null);
    }

    public static StockOperation confirmedStockConsumption(
            UUID id,
            UUID stockOperationTypeId,
            StockOperationDirection direction,
            UUID ownerId,
            StockOperationSource source,
            UUID fromLocationId,
            UUID toLocationId,
            MovementAssignmentPolicy assignmentPolicy,
            Instant enqueuedAt,
            Instant dispatchBy,
            int releasePriority) {
        if (direction == StockOperationDirection.INBOUND) {
            throw new IllegalArgumentException("Inbound operation is a supply operation");
        }
        return new StockOperation(
                id,
                stockOperationTypeId,
                direction,
                ownerId,
                fromLocationId,
                toLocationId,
                source,
                assignmentPolicy,
                enqueuedAt,
                dispatchBy,
                releasePriority,
                StockOperationState.CONFIRMED,
                null);
    }

    public boolean assign() {
        if (state == StockOperationState.ASSIGNED) {
            return false;
        }
        if (state != StockOperationState.CONFIRMED) {
            throw new IllegalStateException("Only a confirmed operation can be assigned, was " + state);
        }
        state = StockOperationState.ASSIGNED;
        return true;
    }

    /** Releases a still-reversible reservation so the same operation can compete for stock again. */
    public boolean unassign() {
        if (state == StockOperationState.CONFIRMED) {
            return false;
        }
        if (state != StockOperationState.ASSIGNED) {
            throw new IllegalStateException("Only an assigned operation can be unassigned, was " + state);
        }
        state = StockOperationState.CONFIRMED;
        return true;
    }

    /** Replay equality deliberately ignores identity, mutable lifecycle state and optimistic version. */
    public boolean hasSameRegistrationContent(StockOperation candidate) {
        return candidate != null
                && stockOperationTypeId.equals(candidate.stockOperationTypeId)
                && direction == candidate.direction
                && ownerId.equals(candidate.ownerId)
                && fromLocationId.equals(candidate.fromLocationId)
                && toLocationId.equals(candidate.toLocationId)
                && Objects.equals(source, candidate.source)
                && assignmentPolicy == candidate.assignmentPolicy
                && Objects.equals(enqueuedAt, candidate.enqueuedAt)
                && Objects.equals(dispatchBy, candidate.dispatchBy)
                && Objects.equals(releasePriority, candidate.releasePriority);
    }

    public boolean complete() {
        if (state == StockOperationState.DONE) {
            return false;
        }
        if (state != StockOperationState.ASSIGNED) {
            throw new IllegalStateException("Only an assigned operation can be completed, was " + state);
        }
        state = StockOperationState.DONE;
        return true;
    }

    public boolean cancel() {
        if (state == StockOperationState.CANCELLED) {
            return false;
        }
        if (state == StockOperationState.DONE) {
            throw new IllegalStateException("A completed operation cannot be cancelled");
        }
        state = StockOperationState.CANCELLED;
        return true;
    }

    public UUID id() {
        return id;
    }

    public UUID stockOperationTypeId() {
        return stockOperationTypeId;
    }

    public StockOperationDirection direction() {
        return direction;
    }

    public UUID ownerId() {
        return ownerId;
    }

    public UUID fromLocationId() {
        return fromLocationId;
    }

    public UUID toLocationId() {
        return toLocationId;
    }

    public StockOperationSource source() {
        return source;
    }

    public MovementAssignmentPolicy assignmentPolicy() {
        return assignmentPolicy;
    }

    public Instant enqueuedAt() {
        return enqueuedAt;
    }

    public Instant dispatchBy() {
        return dispatchBy;
    }

    public Integer releasePriority() {
        return releasePriority;
    }

    public StockOperationState state() {
        return state;
    }

    public Long version() {
        return version;
    }
}
