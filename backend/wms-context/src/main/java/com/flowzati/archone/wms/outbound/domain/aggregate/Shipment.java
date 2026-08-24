package com.flowzati.archone.wms.outbound.domain.aggregate;

import com.flowzati.archone.wms.outbound.domain.entity.PickTask;
import com.flowzati.archone.wms.outbound.domain.entity.WarehouseWork;
import com.flowzati.archone.wms.outbound.domain.exception.ShipmentCancellationRequestConflictException;
import com.flowzati.archone.wms.outbound.domain.type.CancelShipmentStatus;
import com.flowzati.archone.wms.outbound.domain.type.PickTaskStatus;
import com.flowzati.archone.wms.outbound.domain.type.ShipmentCancellationState;
import com.flowzati.archone.wms.outbound.domain.type.ShipmentStatus;
import com.flowzati.archone.wms.outbound.domain.valueobject.ShipmentLine;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 一次由單一 Facility 對客戶執行的出庫交付。
 *
 * <p>它管理 WMS 作業狀態，不修改 StockQuant；完成承運人交接後，由 application use case 明確發布
 * Integration Event 通知 inventory／TMS 邊界。
 */
public class Shipment {

    private final UUID id;
    private final UUID allocationId;
    private final UUID orderId;
    private final UUID ownerId;
    private final UUID facilityId;
    private final List<ShipmentLine> lines;
    private final Instant createdAt;
    private final Instant dispatchBy;
    private final int releasePriority;
    private ShipmentStatus status;
    private UUID waveId;
    private WarehouseWork pickingWork;
    private ShipmentCancellationState cancellationState;
    private UUID cancellationRequestId;
    private Instant cancellationRequestedAt;
    private String cancellationReason;
    private Instant cancelledAt;

    private Shipment(
            UUID id,
            UUID allocationId,
            UUID orderId,
            UUID ownerId,
            UUID facilityId,
            List<ShipmentLine> lines,
            Instant createdAt,
            Instant dispatchBy,
            int releasePriority) {
        if (id == null || allocationId == null || orderId == null || ownerId == null || facilityId == null) {
            throw new IllegalArgumentException("Shipment requires shipment, allocation, order, owner and facility IDs");
        }
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("Shipment requires allocation lines");
        }
        Set<UUID> moveIds = new HashSet<>();
        if (lines.stream().anyMatch(line -> line == null || !moveIds.add(line.moveId()))) {
            throw new IllegalArgumentException("Shipment requires unique non-null allocation lines");
        }
        if (createdAt == null || dispatchBy == null) {
            throw new IllegalArgumentException("Shipment requires creation time and dispatch deadline");
        }
        if (releasePriority < 0 || releasePriority > 100) {
            throw new IllegalArgumentException("Release priority must be between 0 and 100");
        }
        this.id = id;
        this.allocationId = allocationId;
        this.orderId = orderId;
        this.ownerId = ownerId;
        this.facilityId = facilityId;
        this.lines = List.copyOf(lines);
        this.createdAt = createdAt;
        this.dispatchBy = dispatchBy;
        this.releasePriority = releasePriority;
        this.status = ShipmentStatus.CREATED;
    }

    public static Shipment create(
            UUID id,
            UUID allocationId,
            UUID orderId,
            UUID ownerId,
            UUID facilityId,
            List<ShipmentLine> lines,
            Instant dispatchBy,
            int releasePriority,
            Instant createdAt) {
        return new Shipment(
                id, allocationId, orderId, ownerId, facilityId, lines, createdAt, dispatchBy, releasePriority);
    }

    /** 由 persistence adapter 還原完整 aggregate，不重播 command。 */
    public static Shipment rehydrate(
            UUID id,
            UUID allocationId,
            UUID orderId,
            UUID ownerId,
            UUID facilityId,
            List<ShipmentLine> lines,
            Instant dispatchBy,
            int releasePriority,
            Instant createdAt,
            ShipmentStatus status,
            UUID waveId,
            WarehouseWork pickingWork,
            ShipmentCancellationState cancellationState,
            UUID cancellationRequestId,
            Instant cancellationRequestedAt,
            String cancellationReason,
            Instant cancelledAt) {
        Shipment shipment = new Shipment(
                id, allocationId, orderId, ownerId, facilityId, lines, createdAt, dispatchBy, releasePriority);
        if (status == null) {
            throw new IllegalArgumentException("Persisted Shipment status is required");
        }
        if (pickingWork != null && (waveId == null || !pickingWork.belongsTo(waveId, id))) {
            throw new IllegalArgumentException("Persisted WarehouseWork does not belong to Shipment");
        }
        validateCancellationState(
                status,
                cancellationState,
                cancellationRequestId,
                cancellationRequestedAt,
                cancellationReason,
                cancelledAt);
        shipment.status = status;
        shipment.waveId = waveId;
        shipment.pickingWork = pickingWork;
        shipment.cancellationState = cancellationState;
        shipment.cancellationRequestId = cancellationRequestId;
        shipment.cancellationRequestedAt = cancellationRequestedAt;
        shipment.cancellationReason = cancellationReason;
        shipment.cancelledAt = cancelledAt;
        return shipment;
    }

    public void assignToWave(UUID waveId, Instant plannedAt) {
        requireTime(plannedAt, "Wave planning time is required");
        if (waveId == null) {
            throw new IllegalArgumentException("Wave ID is required");
        }
        if (this.waveId != null) {
            if (this.waveId.equals(waveId)) {
                return;
            }
            throw new IllegalStateException("Shipment was already assigned to another Wave");
        }
        requireStatus(ShipmentStatus.CREATED, "Only a created Shipment can enter a Wave");
        this.waveId = waveId;
        status = ShipmentStatus.WAVE_PLANNED;
    }

    public void releaseToWave(UUID waveId, WarehouseWork pickingWork, Instant releasedAt) {
        requireTime(releasedAt, "Release time is required");
        if (hasPassedRelease()) {
            if (!this.waveId.equals(waveId)) {
                throw new IllegalStateException("Shipment was already released by another Wave");
            }
            return;
        }
        requireStatus(ShipmentStatus.WAVE_PLANNED, "Only a Wave-planned Shipment can be released");
        if (waveId == null || pickingWork == null || !pickingWork.belongsTo(waveId, id)) {
            throw new IllegalArgumentException("Wave release requires matching WarehouseWork");
        }
        if (!waveId.equals(this.waveId)) {
            throw new IllegalStateException("Shipment can only be released by its assigned Wave");
        }
        if (pickingWork.pickTasks().size() != lines.size()) {
            throw new IllegalArgumentException("First release policy requires one PickTask per Shipment line");
        }
        requireWorkMatchesLines(pickingWork);
        this.waveId = waveId;
        this.pickingWork = pickingWork;
        status = ShipmentStatus.RELEASED;
    }

    /** Command 可能重送；已越過 release checkpoint 就視為冪等成功。 */
    private boolean hasPassedRelease() {
        return switch (status) {
            case RELEASED, PICKING, PICKED, PACKED, READY_FOR_DISPATCH, HANDED_OVER_TO_CARRIER -> true;
            case CREATED, WAVE_PLANNED, CANCELLING, CANCELLED -> false;
        };
    }

    public void confirmPick(UUID pickTaskId, int actualQuantity, Instant confirmedAt) {
        requireTime(confirmedAt, "Pick confirmation time is required");
        if (pickingWork == null) {
            throw new IllegalStateException("Shipment has no released WarehouseWork");
        }
        PickTask task = pickingWork.pickTasks().stream()
                .filter(candidate -> candidate.id().equals(pickTaskId))
                .findFirst()
                .orElseThrow(
                        () -> new IllegalArgumentException("Pick task does not belong to Shipment: " + pickTaskId));
        if (task.status() == PickTaskStatus.PICKED || task.status() == PickTaskStatus.SHORT_PICKED) {
            return;
        }
        if (status != ShipmentStatus.RELEASED && status != ShipmentStatus.PICKING) {
            throw new IllegalStateException("Only a released or picking shipment accepts pick confirmations");
        }

        status = ShipmentStatus.PICKING;
        task = pickingWork.confirmPick(pickTaskId, actualQuantity, confirmedAt);
        if (task.status() == PickTaskStatus.SHORT_PICKED) {
            return;
        }

        if (pickingWork.isCompleted()) {
            status = ShipmentStatus.PICKED;
        }
    }

    public void pack(Instant packedAt) {
        requireTime(packedAt, "Pack time is required");
        if (status == ShipmentStatus.PACKED) {
            return;
        }
        requireStatus(ShipmentStatus.PICKED, "Only a picked shipment can be packed");
        status = ShipmentStatus.PACKED;
    }

    public void stage(Instant stagedAt) {
        requireTime(stagedAt, "Stage time is required");
        if (status == ShipmentStatus.READY_FOR_DISPATCH) {
            return;
        }
        requireStatus(ShipmentStatus.PACKED, "Only a packed shipment can be staged");
        status = ShipmentStatus.READY_FOR_DISPATCH;
    }

    public boolean handOverToCarrier(Instant handedOverAt) {
        requireTime(handedOverAt, "Handover time is required");
        if (status == ShipmentStatus.HANDED_OVER_TO_CARRIER) {
            return false;
        }
        requireStatus(ShipmentStatus.READY_FOR_DISPATCH, "Only a shipment ready for dispatch can be handed over");
        status = ShipmentStatus.HANDED_OVER_TO_CARRIER;
        return true;
    }

    public CancelShipmentStatus cancel(UUID requestId, Instant requestedAt, String reason, Instant handledAt) {
        if (requestId == null) {
            throw new IllegalArgumentException("Cancellation request ID is required");
        }
        requireTime(requestedAt, "Cancellation request time is required");
        requireTime(handledAt, "Cancellation handling time is required");
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Cancellation reason is required");
        }
        if (reason.length() > 512) {
            throw new IllegalArgumentException("Cancellation reason must not exceed 512 characters");
        }
        if (cancellationState != null) {
            if (!requestId.equals(cancellationRequestId)
                    || !requestedAt.equals(cancellationRequestedAt)
                    || !reason.equals(cancellationReason)) {
                throw new ShipmentCancellationRequestConflictException(
                        "Shipment already has a different immutable cancellation request: " + id);
            }
            return cancellationState == ShipmentCancellationState.REJECTED
                    ? CancelShipmentStatus.REJECTED
                    : CancelShipmentStatus.ALREADY_ACCEPTED;
        }
        cancellationRequestId = requestId;
        cancellationRequestedAt = requestedAt;
        cancellationReason = reason;
        if (status == ShipmentStatus.HANDED_OVER_TO_CARRIER) {
            cancellationState = ShipmentCancellationState.REJECTED;
            return CancelShipmentStatus.REJECTED;
        }
        if (status != ShipmentStatus.CREATED
                && status != ShipmentStatus.WAVE_PLANNED
                && status != ShipmentStatus.RELEASED) {
            status = ShipmentStatus.CANCELLING;
            cancellationState = ShipmentCancellationState.REQUESTED;
            return CancelShipmentStatus.ACCEPTED;
        }

        if (pickingWork != null) {
            pickingWork.cancel();
        }
        status = ShipmentStatus.CANCELLED;
        cancellationState = ShipmentCancellationState.COMPLETED;
        cancelledAt = handledAt;
        return CancelShipmentStatus.ACCEPTED;
    }

    /** WMS 已完成停止作業與必要的實體 recovery；保留原 PickTask 作業歷史。 */
    public boolean completeCancellation(Instant completedAt) {
        requireTime(completedAt, "Cancellation completion time is required");
        if (cancellationState == ShipmentCancellationState.COMPLETED) {
            return false;
        }
        if (status != ShipmentStatus.CANCELLING || cancellationState != ShipmentCancellationState.REQUESTED) {
            throw new IllegalStateException("Only a cancelling Shipment can complete cancellation");
        }
        status = ShipmentStatus.CANCELLED;
        cancellationState = ShipmentCancellationState.COMPLETED;
        cancelledAt = completedAt;
        return true;
    }

    private void requireStatus(ShipmentStatus expected, String message) {
        if (status != expected) {
            throw new IllegalStateException(message + ", was " + status);
        }
    }

    private static void requireTime(Instant time, String message) {
        if (time == null) {
            throw new IllegalArgumentException(message);
        }
    }

    private void requireWorkMatchesLines(WarehouseWork work) {
        boolean everyLineHasMatchingTask = lines.stream()
                .allMatch(line -> work.pickTasks().stream()
                        .anyMatch(task -> task.orderLineId().equals(line.orderLineId())
                                && task.moveId().equals(line.moveId())
                                && task.skuCode().equals(line.skuCode())
                                && task.sourceLocationId().equals(line.sourceLocationId())
                                && task.requestedQuantity() == line.quantity()));
        if (!everyLineHasMatchingTask) {
            throw new IllegalArgumentException("WarehouseWork PickTasks must match Shipment lines");
        }
    }

    public UUID id() {
        return id;
    }

    public UUID allocationId() {
        return allocationId;
    }

    public UUID orderId() {
        return orderId;
    }

    public UUID ownerId() {
        return ownerId;
    }

    public UUID facilityId() {
        return facilityId;
    }

    public List<ShipmentLine> lines() {
        return lines;
    }

    public List<PickTask> pickTasks() {
        return pickingWork == null ? List.of() : pickingWork.pickTasks();
    }

    public Optional<WarehouseWork> pickingWork() {
        return Optional.ofNullable(pickingWork);
    }

    public boolean isWaveCandidate() {
        return status == ShipmentStatus.CREATED && waveId == null;
    }

    /** Wave completion 接受 picking 已完成或 Shipment 已取消，不代表每張 Shipment 都成功揀貨。 */
    public boolean isPickingWorkTerminal() {
        return status == ShipmentStatus.CANCELLED || (pickingWork != null && pickingWork.isTerminal());
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant dispatchBy() {
        return dispatchBy;
    }

    public int releasePriority() {
        return releasePriority;
    }

    public UUID waveId() {
        return waveId;
    }

    public ShipmentStatus status() {
        return status;
    }

    public UUID cancellationRequestId() {
        return cancellationRequestId;
    }

    public Optional<ShipmentCancellationState> cancellationStateValue() {
        return Optional.ofNullable(cancellationState);
    }

    public Instant cancellationRequestedAt() {
        return cancellationRequestedAt;
    }

    public String cancellationReason() {
        return cancellationReason;
    }

    public Instant cancelledAt() {
        return cancelledAt;
    }

    private static void validateCancellationState(
            ShipmentStatus shipmentStatus,
            ShipmentCancellationState cancellationState,
            UUID requestId,
            Instant requestedAt,
            String reason,
            Instant cancelledAt) {
        boolean hasRequest = requestId != null && requestedAt != null && reason != null && !reason.isBlank();
        if (cancellationState == null) {
            if (hasRequest || requestId != null || requestedAt != null || reason != null || cancelledAt != null) {
                throw new IllegalArgumentException("Shipment without cancellation state cannot contain metadata");
            }
            return;
        }
        if (!hasRequest) {
            throw new IllegalArgumentException("Shipment cancellation state requires complete request metadata");
        }
        switch (cancellationState) {
            case REQUESTED -> {
                if (shipmentStatus != ShipmentStatus.CANCELLING || cancelledAt != null) {
                    throw new IllegalArgumentException("Requested cancellation requires a cancelling Shipment");
                }
            }
            case COMPLETED -> {
                if (shipmentStatus != ShipmentStatus.CANCELLED || cancelledAt == null) {
                    throw new IllegalArgumentException("Completed cancellation requires a cancelled Shipment and time");
                }
            }
            case REJECTED -> {
                if (shipmentStatus != ShipmentStatus.HANDED_OVER_TO_CARRIER || cancelledAt != null) {
                    throw new IllegalArgumentException("Rejected cancellation requires a handed-over Shipment");
                }
            }
        }
    }
}
