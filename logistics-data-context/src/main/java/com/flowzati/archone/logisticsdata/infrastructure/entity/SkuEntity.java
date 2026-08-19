package com.flowzati.archone.logisticsdata.infrastructure.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.UUID;

@Entity
@Table(
        name = "skus",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_skus_owner_code",
                        columnNames = {"owner_id", "sku_code"}),
        indexes = @Index(name = "idx_skus_product", columnList = "owner_id,product_code"))
public class SkuEntity {

    @Id
    private UUID id;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(name = "sku_code", nullable = false)
    private String skuCode;

    @Column(name = "product_code", nullable = false)
    private String productCode;

    @Column(name = "spec_name", nullable = false)
    private String specName;

    @Column(name = "weight_gram", nullable = false)
    private int weightGram;

    protected SkuEntity() {}

    public SkuEntity(UUID id, UUID ownerId, String skuCode, String productCode, String specName, int weightGram) {
        this.id = id;
        this.ownerId = ownerId;
        this.skuCode = skuCode;
        this.productCode = productCode;
        this.specName = specName;
        this.weightGram = weightGram;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public String getSkuCode() {
        return skuCode;
    }

    public String getProductCode() {
        return productCode;
    }

    public String getSpecName() {
        return specName;
    }

    public int getWeightGram() {
        return weightGram;
    }
}
