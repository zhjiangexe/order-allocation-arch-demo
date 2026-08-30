package com.flowzati.archone.inventory.reservation.infrastructure.persistence.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/** One read-only Ordering row used to register canonical Inventory movements. */
@Entity
@Immutable
@Table(name = "stock_order_movement_sources")
public class OrderAllocationSourceLineEntity {

    @Id
    @Column(name = "source_line_reference_id")
    private UUID sourceLineReferenceId;

    @Column(name = "source_id")
    private UUID sourceId;

    @Column(name = "owner_id")
    private UUID ownerId;

    @Column(name = "facility_id")
    private UUID facilityId;

    @Column(name = "stock_operation_type_id")
    private UUID stockOperationTypeId;

    @Column(name = "source_location_id")
    private UUID sourceLocationId;

    @Column(name = "destination_location_id")
    private UUID destinationLocationId;

    @Column(name = "required_by")
    private Instant requiredBy;

    @Column(name = "release_priority")
    private int releasePriority;

    @Column(name = "enqueued_at")
    private Instant enqueuedAt;

    @Column(name = "source_line_id")
    private String sourceLineId;

    @Column(name = "sku_code")
    private String skuCode;

    @Column(name = "quantity")
    private int quantity;

    protected OrderAllocationSourceLineEntity() {}

    public UUID getSourceLineReferenceId() {
        return sourceLineReferenceId;
    }

    public UUID getSourceId() {
        return sourceId;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public UUID getFacilityId() {
        return facilityId;
    }

    public UUID getStockOperationTypeId() {
        return stockOperationTypeId;
    }

    public UUID getSourceLocationId() {
        return sourceLocationId;
    }

    public UUID getDestinationLocationId() {
        return destinationLocationId;
    }

    public Instant getRequiredBy() {
        return requiredBy;
    }

    public int getReleasePriority() {
        return releasePriority;
    }

    public Instant getEnqueuedAt() {
        return enqueuedAt;
    }

    public String getSourceLineId() {
        return sourceLineId;
    }

    public String getSkuCode() {
        return skuCode;
    }

    public int getQuantity() {
        return quantity;
    }
}
