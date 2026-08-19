package com.flowzati.archone.wms.outbound.domain.aggregate;

import com.flowzati.archone.wms.outbound.domain.entity.PickTask;
import com.flowzati.archone.wms.outbound.domain.entity.WarehouseWork;
import com.flowzati.archone.wms.outbound.domain.event.PickConfirmed;
import com.flowzati.archone.wms.outbound.domain.event.PickingWorkCreated;
import com.flowzati.archone.wms.outbound.domain.event.ShipmentAssignedToWave;
import com.flowzati.archone.wms.outbound.domain.event.ShipmentCancellationRejected;
import com.flowzati.archone.wms.outbound.domain.event.ShipmentCancelled;
import com.flowzati.archone.wms.outbound.domain.event.ShipmentCreated;
import com.flowzati.archone.wms.outbound.domain.event.ShipmentHandedOverToCarrier;
import com.flowzati.archone.wms.outbound.domain.event.ShipmentPacked;
import com.flowzati.archone.wms.outbound.domain.event.ShipmentPicked;
import com.flowzati.archone.wms.outbound.domain.event.ShipmentPutbackRequired;
import com.flowzati.archone.wms.outbound.domain.event.ShipmentReadyForDispatch;
import com.flowzati.archone.wms.outbound.domain.event.ShipmentReleased;
import com.flowzati.archone.wms.outbound.domain.event.ShipmentStaged;
import com.flowzati.archone.wms.outbound.domain.event.ShortPickDetected;
import com.flowzati.archone.wms.outbound.domain.type.CancellationOutcome;
import com.flowzati.archone.wms.outbound.domain.type.PickTaskStatus;
import com.flowzati.archone.wms.outbound.domain.type.ShipmentStatus;
import com.flowzati.archone.wms.outbound.domain.valueobject.ShipmentLine;
import com.flowzati.archone.wms.shared.domain.WmsDomainEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 一次由單一 Facility 對客戶執行的出庫交付。
 *
 * <p>它管理 WMS 作業狀態，不修改 StockQuant；完成 {@code ShipmentHandedOverToCarrier}
 * 後由 integration adapter 通知 inventory／TMS 邊界。
 */
public class Shipment {

    private final List<WmsDomainEvent> events = new ArrayList<>();
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
    private CancellationOutcome cancellationOutcome;
    private String cancellationRequestId;

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
        Shipment shipment = new Shipment(
                id, allocationId, orderId, ownerId, facilityId, lines, createdAt, dispatchBy, releasePriority);
        shipment.events.add(new ShipmentCreated(id, orderId, allocationId, createdAt));
        return shipment;
    }

    /**
     * 由 persistence adapter 還原完整 aggregate；不重播 command，也不重新產生 domain events。
     */
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
            CancellationOutcome cancellationOutcome,
            String cancellationRequestId) {
        Shipment shipment = new Shipment(
                id, allocationId, orderId, ownerId, facilityId, lines, createdAt, dispatchBy, releasePriority);
        if (status == null) {
            throw new IllegalArgumentException("Persisted Shipment status is required");
        }
        if (pickingWork != null && (waveId == null || !pickingWork.belongsTo(waveId, id))) {
            throw new IllegalArgumentException("Persisted WarehouseWork does not belong to Shipment");
        }
        if (cancellationOutcome == null && cancellationRequestId != null) {
            throw new IllegalArgumentException("Cancellation request requires a persisted outcome");
        }
        shipment.status = status;
        shipment.waveId = waveId;
        shipment.pickingWork = pickingWork;
        shipment.cancellationOutcome = cancellationOutcome;
        shipment.cancellationRequestId = cancellationRequestId;
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
        events.add(new ShipmentAssignedToWave(id, waveId, plannedAt));
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
        events.add(new ShipmentReleased(id, releasedAt));
        events.add(new PickingWorkCreated(
                id,
                waveId,
                pickingWork.id(),
                pickingWork.pickTasks().stream().map(PickTask::id).toList(),
                releasedAt));
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
            events.add(new ShortPickDetected(
                    id,
                    task.id(),
                    task.moveId(),
                    task.skuCode(),
                    task.sourceLocationId(),
                    task.requestedQuantity(),
                    task.pickedQuantity(),
                    confirmedAt));
            return;
        }

        events.add(new PickConfirmed(id, task.id(), task.moveId(), task.pickedQuantity(), confirmedAt));
        if (pickingWork.isCompleted()) {
            status = ShipmentStatus.PICKED;
            events.add(new ShipmentPicked(id, confirmedAt));
        }
    }

    public void pack(Instant packedAt) {
        requireTime(packedAt, "Pack time is required");
        if (status == ShipmentStatus.PACKED) {
            return;
        }
        requireStatus(ShipmentStatus.PICKED, "Only a picked shipment can be packed");
        status = ShipmentStatus.PACKED;
        events.add(new ShipmentPacked(id, packedAt));
    }

    public void stage(Instant stagedAt) {
        requireTime(stagedAt, "Stage time is required");
        if (status == ShipmentStatus.READY_FOR_DISPATCH) {
            return;
        }
        requireStatus(ShipmentStatus.PACKED, "Only a packed shipment can be staged");
        status = ShipmentStatus.READY_FOR_DISPATCH;
        events.add(new ShipmentStaged(id, stagedAt));
        events.add(new ShipmentReadyForDispatch(id, orderId, stagedAt));
    }

    public void handOverToCarrier(Instant handedOverAt) {
        requireTime(handedOverAt, "Handover time is required");
        if (status == ShipmentStatus.HANDED_OVER_TO_CARRIER) {
            return;
        }
        requireStatus(ShipmentStatus.READY_FOR_DISPATCH, "Only a shipment ready for dispatch can be handed over");
        status = ShipmentStatus.HANDED_OVER_TO_CARRIER;
        events.add(new ShipmentHandedOverToCarrier(id, orderId, handedOverAt));
    }

    public CancellationOutcome cancel(String requestId, Instant requestedAt) {
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("Cancellation request ID is required");
        }
        requireTime(requestedAt, "Cancellation request time is required");
        if (cancellationOutcome != null) {
            return cancellationOutcome == CancellationOutcome.CANCELLED
                    ? CancellationOutcome.ALREADY_CANCELLED
                    : cancellationOutcome;
        }
        cancellationRequestId = requestId;
        if (status == ShipmentStatus.HANDED_OVER_TO_CARRIER) {
            events.add(new ShipmentCancellationRejected(
                    id, orderId, requestId, "Shipment already handed over to carrier", requestedAt));
            cancellationOutcome = CancellationOutcome.REJECTED_AFTER_HANDOVER;
            return cancellationOutcome;
        }
        if (status != ShipmentStatus.CREATED
                && status != ShipmentStatus.WAVE_PLANNED
                && status != ShipmentStatus.RELEASED) {
            status = ShipmentStatus.CANCELLING;
            events.add(new ShipmentPutbackRequired(id, orderId, requestId, requestedAt));
            cancellationOutcome = CancellationOutcome.PUTBACK_REQUIRED;
            return cancellationOutcome;
        }

        if (pickingWork != null) {
            pickingWork.cancel();
        }
        status = ShipmentStatus.CANCELLED;
        events.add(new ShipmentCancelled(id, orderId, requestId, requestedAt));
        cancellationOutcome = CancellationOutcome.CANCELLED;
        return cancellationOutcome;
    }

    public List<WmsDomainEvent> releaseEvents() {
        List<WmsDomainEvent> released = List.copyOf(events);
        events.clear();
        return released;
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

    public String cancellationRequestId() {
        return cancellationRequestId;
    }

    public Optional<CancellationOutcome> cancellationOutcomeValue() {
        return Optional.ofNullable(cancellationOutcome);
    }
}
