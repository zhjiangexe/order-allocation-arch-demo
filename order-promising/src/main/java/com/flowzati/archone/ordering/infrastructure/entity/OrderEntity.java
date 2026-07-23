package com.flowzati.archone.ordering.infrastructure.entity;

import com.flowzati.archone.ordering.domain.model.OrderStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
    name = "orders",
    indexes = @Index(
        name = "idx_orders_backorder_fifo",
        columnList = "sku,status,backordered_since,id"
    )
)
public class OrderEntity {

  @Id
  private UUID id;

  @Column(nullable = false)
  private String sku;

  @Column(nullable = false)
  private int quantity;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private OrderStatus status;

  @Column(name = "placed_at", nullable = false)
  private Instant placedAt;

  @Column(name = "allocated_at")
  private Instant allocatedAt;

  @Column(name = "backordered_since")
  private Instant backorderedSince;

  @Column(name = "cancelled_at")
  private Instant cancelledAt;

  @Version
  @Column(nullable = false)
  private Long version;

  protected OrderEntity() {
  }

  public OrderEntity(
      UUID id,
      String sku,
      int quantity,
      OrderStatus status,
      Instant placedAt,
      Instant allocatedAt,
      Instant backorderedSince,
      Instant cancelledAt,
      Long version
  ) {
    this.id = id;
    this.sku = sku;
    this.quantity = quantity;
    this.status = status;
    this.placedAt = placedAt;
    this.allocatedAt = allocatedAt;
    this.backorderedSince = backorderedSince;
    this.cancelledAt = cancelledAt;
    this.version = version;
  }

  public UUID getId() {
    return id;
  }

  public String getSku() {
    return sku;
  }

  public int getQuantity() {
    return quantity;
  }

  public OrderStatus getStatus() {
    return status;
  }

  public Instant getPlacedAt() {
    return placedAt;
  }

  public Instant getAllocatedAt() {
    return allocatedAt;
  }

  public Instant getBackorderedSince() {
    return backorderedSince;
  }

  public Instant getCancelledAt() {
    return cancelledAt;
  }

  public Long getVersion() {
    return version;
  }
}
