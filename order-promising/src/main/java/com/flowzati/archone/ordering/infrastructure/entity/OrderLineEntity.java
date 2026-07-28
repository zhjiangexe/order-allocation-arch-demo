package com.flowzati.archone.ordering.infrastructure.entity;

import com.flowzati.archone.ordering.domain.model.OrderStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
    name = "order_lines",
    indexes = @Index(
        name = "idx_order_lines_backorder_fifo",
        columnList = "owner_id,sku_code,backordered_since,id"
    )
)
public class OrderLineEntity {

  @Id
  private UUID id;

  @Column(name = "line_no", nullable = false)
  private int lineNo;

  @Column(name = "owner_id", nullable = false)
  private UUID ownerId;

  @Column(name = "sku_code", nullable = false)
  private String skuCode;

  @Column(nullable = false)
  private int quantity;


  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private OrderStatus status;

  @Column(name = "backordered_since")
  private Instant backorderedSince;

  protected OrderLineEntity() {
  }

  public OrderLineEntity(
      UUID id,
      int lineNo,
      UUID ownerId,
      String skuCode,
      int quantity,
      OrderStatus status,
      Instant backorderedSince
  ) {
    this.id = id;
    this.lineNo = lineNo;
    this.ownerId = ownerId;
    this.skuCode = skuCode;
    this.quantity = quantity;
    this.status = status;
    this.backorderedSince = backorderedSince;
  }

  public UUID getId() {
    return id;
  }

  public int getLineNo() {
    return lineNo;
  }

  public UUID getOwnerId() {
    return ownerId;
  }

  public String getSkuCode() {
    return skuCode;
  }

  public int getQuantity() {
    return quantity;
  }


  public OrderStatus getStatus() {
    return status;
  }

  public Instant getBackorderedSince() {
    return backorderedSince;
  }
}
