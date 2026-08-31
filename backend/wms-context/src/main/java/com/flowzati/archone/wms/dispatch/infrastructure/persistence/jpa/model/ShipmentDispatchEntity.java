package com.flowzati.archone.wms.dispatch.infrastructure.persistence.jpa.model;

import com.flowzati.archone.wms.dispatch.domain.type.ShipmentDispatchStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "wms_dispatches")
public class ShipmentDispatchEntity {

    @Id
    private UUID id;

    @Column(name = "shipment_id", nullable = false, unique = true)
    private UUID shipmentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ShipmentDispatchStatus status;

    @Column(name = "packed_at", nullable = false)
    private Instant packedAt;

    @Column(name = "staged_at")
    private Instant stagedAt;

    @Column(name = "handed_over_at")
    private Instant handedOverAt;

    @Version
    @Column(nullable = false)
    private Long version;

    protected ShipmentDispatchEntity() {}

    public ShipmentDispatchEntity(UUID id) {
        this.id = Objects.requireNonNull(id, "ShipmentDispatch ID is required");
    }

    public UUID getId() {
        return id;
    }

    public UUID getShipmentId() {
        return shipmentId;
    }

    public void setShipmentId(UUID shipmentId) {
        this.shipmentId = shipmentId;
    }

    public ShipmentDispatchStatus getStatus() {
        return status;
    }

    public void setStatus(ShipmentDispatchStatus status) {
        this.status = status;
    }

    public Instant getPackedAt() {
        return packedAt;
    }

    public void setPackedAt(Instant packedAt) {
        this.packedAt = packedAt;
    }

    public Instant getStagedAt() {
        return stagedAt;
    }

    public void setStagedAt(Instant stagedAt) {
        this.stagedAt = stagedAt;
    }

    public Instant getHandedOverAt() {
        return handedOverAt;
    }

    public void setHandedOverAt(Instant handedOverAt) {
        this.handedOverAt = handedOverAt;
    }
}
