package com.flowzati.archone.wms.picking.domain.entity;

import com.flowzati.archone.wms.picking.domain.type.PickTaskStatus;
import java.time.Instant;
import java.util.UUID;

/** 現場實揀任務；不修改庫存，貨物完成 custody handover 後才由 inventory 正式完成 StockMove。 */
public class PickTask {

    private final UUID id;
    private final UUID orderLineId;
    private final UUID moveId;
    private final String skuCode;
    private final UUID sourceLocationId;
    private final int requestedQuantity;
    private int pickedQuantity;
    private PickTaskStatus status;
    private Instant confirmedAt;

    public PickTask(
            UUID id, UUID orderLineId, UUID moveId, String skuCode, UUID sourceLocationId, int requestedQuantity) {
        if (id == null || orderLineId == null || moveId == null || sourceLocationId == null) {
            throw new IllegalArgumentException("Pick task requires task, order line, move and location IDs");
        }
        if (skuCode == null || skuCode.isBlank()) {
            throw new IllegalArgumentException("Pick task SKU is required");
        }
        if (requestedQuantity <= 0) {
            throw new IllegalArgumentException("Requested pick quantity must be positive");
        }
        this.id = id;
        this.orderLineId = orderLineId;
        this.moveId = moveId;
        this.skuCode = skuCode;
        this.sourceLocationId = sourceLocationId;
        this.requestedQuantity = requestedQuantity;
        this.status = PickTaskStatus.PENDING;
    }

    public static PickTask rehydrate(
            UUID id,
            UUID orderLineId,
            UUID moveId,
            String skuCode,
            UUID sourceLocationId,
            int requestedQuantity,
            int pickedQuantity,
            PickTaskStatus status,
            Instant confirmedAt) {
        PickTask task = new PickTask(id, orderLineId, moveId, skuCode, sourceLocationId, requestedQuantity);
        if (status == null || pickedQuantity < 0 || pickedQuantity > requestedQuantity) {
            throw new IllegalArgumentException("Persisted PickTask state is invalid");
        }
        boolean confirmed = status == PickTaskStatus.PICKED || status == PickTaskStatus.SHORT_PICKED;
        if (confirmed != (confirmedAt != null)) {
            throw new IllegalArgumentException("Persisted PickTask status and confirmation time disagree");
        }
        if (status == PickTaskStatus.PICKED && pickedQuantity != requestedQuantity) {
            throw new IllegalArgumentException("Picked task must contain its complete quantity");
        }
        if (status == PickTaskStatus.SHORT_PICKED && pickedQuantity >= requestedQuantity) {
            throw new IllegalArgumentException("Short-picked task must be below requested quantity");
        }
        if (!confirmed && pickedQuantity != 0) {
            throw new IllegalArgumentException("Unconfirmed PickTask cannot contain a picked quantity");
        }
        task.pickedQuantity = pickedQuantity;
        task.status = status;
        task.confirmedAt = confirmedAt;
        return task;
    }

    public PickTaskStatus confirm(int actualQuantity, Instant confirmedAt) {
        if (status == PickTaskStatus.PICKED || status == PickTaskStatus.SHORT_PICKED) {
            return status;
        }
        if (status != PickTaskStatus.PENDING) {
            throw new IllegalStateException("Only a pending pick task can be confirmed, was " + status);
        }
        if (actualQuantity < 0 || actualQuantity > requestedQuantity) {
            throw new IllegalArgumentException("Actual quantity must be between zero and requested quantity");
        }
        if (confirmedAt == null) {
            throw new IllegalArgumentException("Pick confirmation time is required");
        }
        pickedQuantity = actualQuantity;
        this.confirmedAt = confirmedAt;
        status = actualQuantity == requestedQuantity ? PickTaskStatus.PICKED : PickTaskStatus.SHORT_PICKED;
        return status;
    }

    public boolean cancel() {
        if (status == PickTaskStatus.CANCELLED) {
            return false;
        }
        if (status != PickTaskStatus.PENDING) {
            throw new IllegalStateException("A started pick task requires physical putback");
        }
        status = PickTaskStatus.CANCELLED;
        return true;
    }

    public UUID id() {
        return id;
    }

    public UUID orderLineId() {
        return orderLineId;
    }

    public UUID moveId() {
        return moveId;
    }

    public String skuCode() {
        return skuCode;
    }

    public UUID sourceLocationId() {
        return sourceLocationId;
    }

    public int requestedQuantity() {
        return requestedQuantity;
    }

    public int pickedQuantity() {
        return pickedQuantity;
    }

    public PickTaskStatus status() {
        return status;
    }

    public Instant confirmedAt() {
        return confirmedAt;
    }
}
