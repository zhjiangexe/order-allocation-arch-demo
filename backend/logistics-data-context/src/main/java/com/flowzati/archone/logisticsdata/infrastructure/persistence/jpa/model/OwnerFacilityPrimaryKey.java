package com.flowzati.archone.logisticsdata.infrastructure.persistence.jpa.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** {@code owner_facilities} 的複合主鍵（貨主＋倉庫）；以 {@code @EmbeddedId} 使用。 */
@Embeddable
public class OwnerFacilityPrimaryKey implements Serializable {

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(name = "facility_id", nullable = false)
    private UUID facilityId;

    protected OwnerFacilityPrimaryKey() {}

    public OwnerFacilityPrimaryKey(UUID ownerId, UUID facilityId) {
        this.ownerId = ownerId;
        this.facilityId = facilityId;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public UUID getFacilityId() {
        return facilityId;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof OwnerFacilityPrimaryKey that)) {
            return false;
        }
        return Objects.equals(ownerId, that.ownerId) && Objects.equals(facilityId, that.facilityId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(ownerId, facilityId);
    }
}
