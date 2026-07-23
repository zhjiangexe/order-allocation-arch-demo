package com.flowzati.archone.allocation.infrastructure.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(
    name = "stock_pools",
    uniqueConstraints = @UniqueConstraint(name = "uq_stock_pools_sku", columnNames = "sku")
)
public class StockPoolEntity {

  @Id
  private Long id;

  @Column(nullable = false)
  private String sku;

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

  protected StockPoolEntity() {
  }

  public StockPoolEntity(
      Long id,
      String sku,
      int onHandQuantity,
      int reservedQuantity,
      Long version
  ) {
    this.id = id;
    this.sku = sku;
    this.onHandQuantity = onHandQuantity;
    this.reservedQuantity = reservedQuantity;
    this.version = version;
  }

  public Long getId() {
    return id;
  }

  public String getSku() {
    return sku;
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
