package com.flowzati.archone.inventory.movement.infrastructure.persistence.jpa.entity;

import com.flowzati.archone.inventory.movement.domain.valueobject.StockOperationDirection;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.UUID;

@Entity
@Table(
        name = "stock_operation_types",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_stock_operation_types_facility_code",
                        columnNames = {"facility_id", "code"}))
public class StockOperationTypeEntity {

    @Id
    private UUID id;

    @Column(name = "facility_id", nullable = false)
    private UUID facilityId;

    /** 以名稱而非序數存，理由與 {@code StockLocationEntity.usage} 相同。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private StockOperationDirection code;

    @Column(nullable = false)
    private String name;

    @Column(name = "default_from_location_id", nullable = false)
    private UUID defaultFromLocationId;

    @Column(name = "default_to_location_id", nullable = false)
    private UUID defaultToLocationId;

    protected StockOperationTypeEntity() {}

    public StockOperationTypeEntity(
            UUID id,
            UUID facilityId,
            StockOperationDirection code,
            String name,
            UUID defaultFromLocationId,
            UUID defaultToLocationId) {
        this.id = id;
        this.facilityId = facilityId;
        this.code = code;
        this.name = name;
        this.defaultFromLocationId = defaultFromLocationId;
        this.defaultToLocationId = defaultToLocationId;
    }

    public UUID getId() {
        return id;
    }

    public UUID getFacilityId() {
        return facilityId;
    }

    public StockOperationDirection getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public UUID getDefaultFromLocationId() {
        return defaultFromLocationId;
    }

    public UUID getDefaultToLocationId() {
        return defaultToLocationId;
    }
}
