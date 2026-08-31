package com.flowzati.archone.wms.dispatch.domain.aggregate;

import com.flowzati.archone.wms.dispatch.domain.type.ShipmentDispatchStatus;
import java.time.Instant;
import java.util.UUID;

/** Shipment 完成揀貨後的包裝、集貨與 custody handover aggregate。 */
public class ShipmentDispatch {

    private final UUID id;
    private final UUID shipmentId;
    private final Instant packedAt;
    private ShipmentDispatchStatus status;
    private Instant stagedAt;
    private Instant handedOverAt;

    private ShipmentDispatch(UUID id, UUID shipmentId, Instant packedAt) {
        if (id == null || shipmentId == null || packedAt == null) {
            throw new IllegalArgumentException("ShipmentDispatch requires IDs and packing time");
        }
        this.id = id;
        this.shipmentId = shipmentId;
        this.packedAt = packedAt;
        status = ShipmentDispatchStatus.PACKED;
    }

    public static ShipmentDispatch pack(UUID id, UUID shipmentId, Instant packedAt) {
        return new ShipmentDispatch(id, shipmentId, packedAt);
    }

    public static ShipmentDispatch rehydrate(
            UUID id,
            UUID shipmentId,
            Instant packedAt,
            ShipmentDispatchStatus status,
            Instant stagedAt,
            Instant handedOverAt) {
        ShipmentDispatch shipmentDispatch = new ShipmentDispatch(id, shipmentId, packedAt);
        if (status == null
                || ((status == ShipmentDispatchStatus.STAGED || status == ShipmentDispatchStatus.HANDED_OVER)
                        && stagedAt == null)
                || (status == ShipmentDispatchStatus.HANDED_OVER && handedOverAt == null)
                || (status != ShipmentDispatchStatus.HANDED_OVER && handedOverAt != null)) {
            throw new IllegalArgumentException("Persisted ShipmentDispatch state is invalid");
        }
        shipmentDispatch.status = status;
        shipmentDispatch.stagedAt = stagedAt;
        shipmentDispatch.handedOverAt = handedOverAt;
        return shipmentDispatch;
    }

    public boolean stage(Instant stagedAt) {
        requireTime(stagedAt, "Staging time is required");
        if (status == ShipmentDispatchStatus.STAGED || status == ShipmentDispatchStatus.HANDED_OVER) {
            return false;
        }
        if (status != ShipmentDispatchStatus.PACKED) {
            throw new IllegalStateException("Only a packed ShipmentDispatch can be staged");
        }
        this.stagedAt = stagedAt;
        status = ShipmentDispatchStatus.STAGED;
        return true;
    }

    public boolean handOver(Instant handedOverAt) {
        requireTime(handedOverAt, "Handover time is required");
        if (status == ShipmentDispatchStatus.HANDED_OVER) {
            return false;
        }
        if (status != ShipmentDispatchStatus.STAGED) {
            throw new IllegalStateException("Only a staged ShipmentDispatch can be handed over");
        }
        this.handedOverAt = handedOverAt;
        status = ShipmentDispatchStatus.HANDED_OVER;
        return true;
    }

    /** 包裝或集貨後取消；實體回復已完成，保留既有時間戳作 audit trail。 */
    public boolean cancel() {
        if (status == ShipmentDispatchStatus.CANCELLED) {
            return false;
        }
        if (status == ShipmentDispatchStatus.HANDED_OVER) {
            throw new IllegalStateException("A handed-over ShipmentDispatch cannot be cancelled");
        }
        status = ShipmentDispatchStatus.CANCELLED;
        return true;
    }

    private static void requireTime(Instant time, String message) {
        if (time == null) {
            throw new IllegalArgumentException(message);
        }
    }

    public UUID id() {
        return id;
    }

    public UUID shipmentId() {
        return shipmentId;
    }

    public ShipmentDispatchStatus status() {
        return status;
    }

    public Instant packedAt() {
        return packedAt;
    }

    public Instant stagedAt() {
        return stagedAt;
    }

    public Instant handedOverAt() {
        return handedOverAt;
    }
}
