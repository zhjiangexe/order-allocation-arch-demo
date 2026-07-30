package com.flowzati.archone.allocation.infrastructure.entity;

import com.flowzati.archone.allocation.domain.model.ReservationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
    name = "stock_reservations",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_stock_reservations_line_pool",
        columnNames = {"order_line_id", "stock_pool_id"}
    ),
    indexes = {
        @Index(name = "idx_stock_reservations_line", columnList = "order_line_id"),
        @Index(name = "idx_stock_reservations_pool", columnList = "stock_pool_id")
    }
)
public class StockReservationEntity {

  @Id
  private UUID id;

  @Column(name = "order_line_id", nullable = false)
  private UUID orderLineId;

  @Column(name = "stock_pool_id", nullable = false)
  private UUID stockPoolId;

  @Column(nullable = false)
  private int quantity;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private ReservationStatus status;

  @Column(name = "reserved_at", nullable = false)
  private Instant reservedAt;

  @Column(name = "released_at")
  private Instant releasedAt;

  @Version
  @Column(nullable = false)
  private Long version;

  protected StockReservationEntity() {
  }

  public StockReservationEntity(
      UUID id,
      UUID orderLineId,
      UUID stockPoolId,
      int quantity,
      ReservationStatus status,
      Instant reservedAt,
      Instant releasedAt,
      Long version
  ) {
    this.id = id;
    this.orderLineId = orderLineId;
    this.stockPoolId = stockPoolId;
    this.quantity = quantity;
    this.status = status;
    this.reservedAt = reservedAt;
    this.releasedAt = releasedAt;
    this.version = version;
  }

  public UUID getId() {
    return id;
  }

  public UUID getOrderLineId() {
    return orderLineId;
  }

  public UUID getStockPoolId() {
    return stockPoolId;
  }

  public int getQuantity() {
    return quantity;
  }

  public ReservationStatus getStatus() {
    return status;
  }

  public Instant getReservedAt() {
    return reservedAt;
  }

  public Instant getReleasedAt() {
    return releasedAt;
  }

  public Long getVersion() {
    return version;
  }
}
