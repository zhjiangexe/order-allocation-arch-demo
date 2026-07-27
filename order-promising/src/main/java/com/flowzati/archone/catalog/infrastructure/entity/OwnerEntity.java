package com.flowzati.archone.catalog.infrastructure.entity;

import com.flowzati.archone.catalog.domain.model.OwnerStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "owners")
public class OwnerEntity {

  @Id
  private UUID id;

  @Column(nullable = false)
  private String code;

  @Column(nullable = false)
  private String name;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private OwnerStatus status;

  @Column(name = "allow_split_shipment", nullable = false)
  private boolean allowSplitShipment;

  protected OwnerEntity() {
  }

  public OwnerEntity(
      UUID id,
      String code,
      String name,
      OwnerStatus status,
      boolean allowSplitShipment
  ) {
    this.id = id;
    this.code = code;
    this.name = name;
    this.status = status;
    this.allowSplitShipment = allowSplitShipment;
  }

  public UUID getId() {
    return id;
  }

  public String getCode() {
    return code;
  }

  public String getName() {
    return name;
  }

  public OwnerStatus getStatus() {
    return status;
  }

  public boolean isAllowSplitShipment() {
    return allowSplitShipment;
  }
}
