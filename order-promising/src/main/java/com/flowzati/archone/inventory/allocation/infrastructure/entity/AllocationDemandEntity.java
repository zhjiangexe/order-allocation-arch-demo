package com.flowzati.archone.inventory.allocation.infrastructure.entity;

import com.flowzati.archone.inventory.allocation.domain.type.AllocationDemandStatus;
import com.flowzati.archone.inventory.allocation.domain.type.AllocationSourceType;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "allocation_demands")
public class AllocationDemandEntity {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false)
    private AllocationSourceType sourceType;

    @Column(name = "source_id", nullable = false)
    private String sourceId;

    @Column(name = "allocation_unit_key", nullable = false)
    private String allocationUnitKey;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(name = "facility_id", nullable = false)
    private UUID facilityId;

    @Column(name = "location_id", nullable = false)
    private UUID locationId;

    @Column(name = "required_by", nullable = false)
    private Instant requiredBy;

    @Column(name = "release_priority", nullable = false)
    private int releasePriority;

    @Column(name = "enqueued_at", nullable = false)
    private Instant enqueuedAt;

    @Column(name = "accepted_content_version", nullable = false)
    private int acceptedContentVersion;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "allocation_demand_id", nullable = false)
    @OrderBy("lineSequence ASC")
    private List<AllocationDemandLineEntity> lines;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AllocationDemandStatus status;

    @Version
    @Column(nullable = false)
    private Long version;

    protected AllocationDemandEntity() {}

    public AllocationDemandEntity(
            UUID id,
            AllocationSourceType sourceType,
            String sourceId,
            String allocationUnitKey,
            UUID ownerId,
            UUID facilityId,
            UUID locationId,
            Instant requiredBy,
            int releasePriority,
            Instant enqueuedAt,
            int acceptedContentVersion,
            List<AllocationDemandLineEntity> lines,
            AllocationDemandStatus status,
            Long version) {
        this.id = id;
        this.sourceType = sourceType;
        this.sourceId = sourceId;
        this.allocationUnitKey = allocationUnitKey;
        this.ownerId = ownerId;
        this.facilityId = facilityId;
        this.locationId = locationId;
        this.requiredBy = requiredBy;
        this.releasePriority = releasePriority;
        this.enqueuedAt = enqueuedAt;
        this.acceptedContentVersion = acceptedContentVersion;
        this.lines = new ArrayList<>(lines);
        this.status = status;
        this.version = version;
    }

    public UUID getId() {
        return id;
    }

    public AllocationSourceType getSourceType() {
        return sourceType;
    }

    public String getSourceId() {
        return sourceId;
    }

    public String getAllocationUnitKey() {
        return allocationUnitKey;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public UUID getFacilityId() {
        return facilityId;
    }

    public UUID getLocationId() {
        return locationId;
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

    public int getAcceptedContentVersion() {
        return acceptedContentVersion;
    }

    public List<AllocationDemandLineEntity> getLines() {
        return lines;
    }

    public AllocationDemandStatus getStatus() {
        return status;
    }

    public Long getVersion() {
        return version;
    }
}
