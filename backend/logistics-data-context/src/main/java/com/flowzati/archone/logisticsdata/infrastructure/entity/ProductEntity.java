package com.flowzati.archone.logisticsdata.infrastructure.entity;

import com.flowzati.archone.logisticsdata.domain.type.TemperatureZoneType;
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
        name = "products",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_products_owner_code",
                        columnNames = {"owner_id", "product_code"}))
public class ProductEntity {

    @Id
    private UUID id;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(name = "product_code", nullable = false)
    private String productCode;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "temperature_zone", nullable = false)
    private TemperatureZoneType temperatureZone;

    protected ProductEntity() {}

    public ProductEntity(UUID id, UUID ownerId, String productCode, String name, TemperatureZoneType temperatureZone) {
        this.id = id;
        this.ownerId = ownerId;
        this.productCode = productCode;
        this.name = name;
        this.temperatureZone = temperatureZone;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public String getProductCode() {
        return productCode;
    }

    public String getName() {
        return name;
    }

    public TemperatureZoneType getTemperatureZone() {
        return temperatureZone;
    }
}
