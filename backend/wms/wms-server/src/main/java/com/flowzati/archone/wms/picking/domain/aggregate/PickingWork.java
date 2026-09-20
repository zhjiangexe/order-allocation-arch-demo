package com.flowzati.archone.wms.picking.domain.aggregate;

import com.flowzati.archone.wms.picking.domain.entity.PickTask;
import com.flowzati.archone.wms.picking.domain.type.PickTaskStatus;
import com.flowzati.archone.wms.picking.domain.type.PickingWorkStatus;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Wave release 後交給現場執行的 picking aggregate；第一版一張 Shipment 對一個 PickingWork。 */
public class PickingWork {

    private final UUID id;
    private final UUID waveId;
    private final UUID shipmentId;
    private final List<PickTask> pickTasks;
    private PickingWorkStatus status;

    public PickingWork(UUID id, UUID waveId, UUID shipmentId, List<PickTask> pickTasks) {
        if (id == null || waveId == null || shipmentId == null) {
            throw new IllegalArgumentException("Picking work requires work, Wave and Shipment IDs");
        }
        if (pickTasks == null || pickTasks.isEmpty()) {
            throw new IllegalArgumentException("Picking work requires PickTasks");
        }
        requireUniqueTaskIds(pickTasks);
        this.id = id;
        this.waveId = waveId;
        this.shipmentId = shipmentId;
        this.pickTasks = List.copyOf(pickTasks);
        status = PickingWorkStatus.OPEN;
    }

    public static PickingWork rehydrate(
            UUID id, UUID waveId, UUID shipmentId, List<PickTask> pickTasks, PickingWorkStatus status) {
        PickingWork work = new PickingWork(id, waveId, shipmentId, pickTasks);
        if (status == null) {
            throw new IllegalArgumentException("Persisted PickingWork status is required");
        }
        work.status = status;
        return work;
    }

    public PickTask confirmPick(UUID pickTaskId, int actualQuantity, Instant confirmedAt) {
        PickTask task = requiredTask(pickTaskId);
        if (task.status() == PickTaskStatus.PICKED || task.status() == PickTaskStatus.SHORT_PICKED) {
            return task;
        }
        if (status == PickingWorkStatus.CANCELLED || status == PickingWorkStatus.COMPLETED) {
            throw new IllegalStateException("Picking work no longer accepts confirmations: " + status);
        }
        task.confirm(actualQuantity, confirmedAt);
        refreshStatus();
        return task;
    }

    public void cancel() {
        if (status == PickingWorkStatus.CANCELLED) {
            return;
        }
        if (status != PickingWorkStatus.OPEN) {
            throw new IllegalStateException("Started PickingWork requires physical recovery");
        }
        pickTasks.forEach(PickTask::cancel);
        status = PickingWorkStatus.CANCELLED;
    }

    /** 實體 putback／recovery 已由現場完成；保留已確認 task 的歷史，只取消尚未開始的 task。 */
    public void completeCancellationRecovery() {
        if (status == PickingWorkStatus.CANCELLED) {
            return;
        }
        pickTasks.stream()
                .filter(task -> task.status() == PickTaskStatus.PENDING)
                .forEach(PickTask::cancel);
        status = PickingWorkStatus.CANCELLED;
    }

    public boolean isTerminal() {
        return status == PickingWorkStatus.COMPLETED || status == PickingWorkStatus.CANCELLED;
    }

    private PickTask requiredTask(UUID pickTaskId) {
        return pickTasks.stream()
                .filter(candidate -> candidate.id().equals(pickTaskId))
                .findFirst()
                .orElseThrow(
                        () -> new IllegalArgumentException("Pick task does not belong to PickingWork: " + pickTaskId));
    }

    private void refreshStatus() {
        if (pickTasks.stream().allMatch(task -> task.status() == PickTaskStatus.PICKED)) {
            status = PickingWorkStatus.COMPLETED;
        } else if (pickTasks.stream().anyMatch(task -> task.status() == PickTaskStatus.SHORT_PICKED)) {
            status = PickingWorkStatus.EXCEPTION;
        } else {
            status = PickingWorkStatus.IN_PROGRESS;
        }
    }

    private static void requireUniqueTaskIds(List<PickTask> pickTasks) {
        Set<UUID> taskIds = new HashSet<>();
        if (pickTasks.stream().anyMatch(task -> task == null || !taskIds.add(task.id()))) {
            throw new IllegalArgumentException("PickingWork cannot contain null or duplicate PickTasks");
        }
    }

    public UUID id() {
        return id;
    }

    public UUID waveId() {
        return waveId;
    }

    public UUID shipmentId() {
        return shipmentId;
    }

    public List<PickTask> pickTasks() {
        return pickTasks;
    }

    public PickingWorkStatus status() {
        return status;
    }
}
