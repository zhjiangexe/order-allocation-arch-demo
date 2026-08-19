package com.flowzati.archone.inventory.balance.infrastructure.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Index;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(
    name = "stock_pools",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_stock_pools_batch",
        columnNames = {"owner_id", "location_id", "sku_code", "in_date", "expiry_date"}
    ),
    indexes = @Index(
        name = "idx_stock_pools_fefo",
        columnList = "owner_id,location_id,sku_code,expiry_date,in_date,id"
    )
)
public class StockQuantEntity {

  @Id
  private UUID id;

  @Column(name = "owner_id", nullable = false)
  private UUID ownerId;

  @Column(name = "location_id", nullable = false)
  private UUID locationId;

  @Column(name = "sku_code", nullable = false)
  private String skuCode;

  @Column(name = "in_date", nullable = false)
  private LocalDate inDate;

  @Column(name = "expiry_date", nullable = false)
  private LocalDate expiryDate;

  @Column(name = "on_hand_quantity", nullable = false)
  private int onHandQuantity;

  @Column(name = "reserved_quantity", nullable = false)
  private int reservedQuantity;

  @Version
  @Column(nullable = false)
  private Long version;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected StockQuantEntity() {
  }

  public StockQuantEntity(
      UUID id,
      UUID ownerId,
      UUID locationId,
      String skuCode,
      LocalDate inDate,
      LocalDate expiryDate,
      int onHandQuantity,
      int reservedQuantity,
      Long version
  ) {
    this.id = id;
    this.ownerId = ownerId;
    this.locationId = locationId;
    this.skuCode = skuCode;
    this.inDate = inDate;
    this.expiryDate = expiryDate;
    this.onHandQuantity = onHandQuantity;
    this.reservedQuantity = reservedQuantity;
    this.version = version;
  }

  public UUID getId() {
    return id;
  }

  public UUID getOwnerId() {
    return ownerId;
  }

  public UUID getLocationId() {
    return locationId;
  }

  public String getSkuCode() {
    return skuCode;
  }

  public LocalDate getInDate() {
    return inDate;
  }

  public LocalDate getExpiryDate() {
    return expiryDate;
  }

  public int getOnHandQuantity() {
    return onHandQuantity;
  }

  public int getReservedQuantity() {
    return reservedQuantity;
  }

  public Long getVersion() {
    return version;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
