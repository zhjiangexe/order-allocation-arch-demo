package com.flowzati.archone.wms.outbound.domain.entity;

import com.flowzati.archone.wms.outbound.domain.type.PickTaskStatus;
import com.flowzati.archone.wms.outbound.domain.type.WarehouseWorkStatus;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Wave Release 後可交給現場執行的一組 picking tasks。
 *
 * <p>第一版一張 Shipment 對一個 WarehouseWork；日後 Zone／Cluster picking 出現後，才依實際
 * work ownership 拆成多個 Work，不預先複製複雜模型。
 */
public class WarehouseWork {

  private final UUID id;
  private final UUID waveId;
  private final UUID shipmentId;
  private final List<PickTask> pickTasks;
  private WarehouseWorkStatus status;

  public WarehouseWork(
      UUID id,
      UUID waveId,
      UUID shipmentId,
      List<PickTask> pickTasks
  ) {
    if (id == null || waveId == null || shipmentId == null) {
      throw new IllegalArgumentException("Warehouse work requires work, Wave and Shipment IDs");
    }
    if (pickTasks == null || pickTasks.isEmpty()) {
      throw new IllegalArgumentException("Warehouse work requires PickTasks");
    }
    requireUniqueTaskIds(pickTasks);
    this.id = id;
    this.waveId = waveId;
    this.shipmentId = shipmentId;
    this.pickTasks = List.copyOf(pickTasks);
    this.status = WarehouseWorkStatus.OPEN;
  }

  /** 由 persistence adapter 還原；狀態仍由 work 自己持有，不由 JPA entity 暴露行為。 */
  public static WarehouseWork rehydrate(
      UUID id,
      UUID waveId,
      UUID shipmentId,
      List<PickTask> pickTasks,
      WarehouseWorkStatus status
  ) {
    WarehouseWork work = new WarehouseWork(id, waveId, shipmentId, pickTasks);
    if (status == null) {
      throw new IllegalArgumentException("Persisted WarehouseWork status is required");
    }
    work.status = status;
    return work;
  }

  public PickTask confirmPick(UUID pickTaskId, int actualQuantity, java.time.Instant confirmedAt) {
    PickTask task = requiredTask(pickTaskId);
    if (task.status() == PickTaskStatus.PICKED
        || task.status() == PickTaskStatus.SHORT_PICKED) {
      return task;
    }
    if (status == WarehouseWorkStatus.CANCELLED || status == WarehouseWorkStatus.COMPLETED) {
      throw new IllegalStateException("Warehouse work no longer accepts Pick confirmations: " + status);
    }
    task.confirm(actualQuantity, confirmedAt);
    refreshStatus();
    return task;
  }

  public void cancel() {
    if (status == WarehouseWorkStatus.CANCELLED) {
      return;
    }
    if (status != WarehouseWorkStatus.OPEN) {
      throw new IllegalStateException("Started WarehouseWork requires physical recovery");
    }
    pickTasks.forEach(PickTask::cancel);
    status = WarehouseWorkStatus.CANCELLED;
  }

  public boolean isCompleted() {
    return status == WarehouseWorkStatus.COMPLETED;
  }

  public boolean isTerminal() {
    return status == WarehouseWorkStatus.COMPLETED
        || status == WarehouseWorkStatus.CANCELLED;
  }

  public boolean belongsTo(UUID expectedWaveId, UUID expectedShipmentId) {
    return waveId.equals(expectedWaveId) && shipmentId.equals(expectedShipmentId);
  }

  private PickTask requiredTask(UUID pickTaskId) {
    return pickTasks.stream()
        .filter(candidate -> candidate.id().equals(pickTaskId))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException(
            "Pick task does not belong to WarehouseWork: " + pickTaskId));
  }

  private void refreshStatus() {
    if (pickTasks.stream().allMatch(task -> task.status() == PickTaskStatus.PICKED)) {
      status = WarehouseWorkStatus.COMPLETED;
      return;
    }
    if (pickTasks.stream().anyMatch(task -> task.status() == PickTaskStatus.SHORT_PICKED)) {
      status = WarehouseWorkStatus.EXCEPTION;
      return;
    }
    status = WarehouseWorkStatus.IN_PROGRESS;
  }

  private static void requireUniqueTaskIds(List<PickTask> pickTasks) {
    Set<UUID> taskIds = new HashSet<>();
    for (PickTask task : pickTasks) {
      if (task == null || !taskIds.add(task.id())) {
        throw new IllegalArgumentException("WarehouseWork cannot contain null or duplicate PickTasks");
      }
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

  public WarehouseWorkStatus status() {
    return status;
  }
}
