package com.flowzati.archone.stock.allocation.infrastructure.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "allocation_demand_lines")
public class AllocationDemandLineEntity {

  @Id
  private UUID id;

  @Column(name = "source_line_id", nullable = false)
  private String sourceLineId;

  @Column(name = "sku_code", nullable = false)
  private String skuCode;

  @Column(nullable = false)
  private int quantity;

  @Column(name = "line_sequence", nullable = false)
  private int lineSequence;

  protected AllocationDemandLineEntity() {
  }

  public AllocationDemandLineEntity(
      UUID id, String sourceLineId, String skuCode, int quantity, int lineSequence) {
    this.id = id;
    this.sourceLineId = sourceLineId;
    this.skuCode = skuCode;
    this.quantity = quantity;
    this.lineSequence = lineSequence;
  }

  public UUID getId() {
    return id;
  }

  public String getSourceLineId() {
    return sourceLineId;
  }

  public String getSkuCode() {
    return skuCode;
  }

  public int getQuantity() {
    return quantity;
  }

  public int getLineSequence() {
    return lineSequence;
  }
}
