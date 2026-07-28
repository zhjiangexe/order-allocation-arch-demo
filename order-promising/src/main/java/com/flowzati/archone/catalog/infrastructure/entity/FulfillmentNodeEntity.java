package com.flowzati.archone.catalog.infrastructure.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.UUID;

@Entity
@Table(
    name = "fulfillment_nodes",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_fulfillment_nodes_code",
        columnNames = {"code"}
    )
)
public class FulfillmentNodeEntity {

  @Id
  private UUID id;

  @Column(nullable = false)
  private String code;

  @Column(nullable = false)
  private String name;

  protected FulfillmentNodeEntity() {
  }

  public FulfillmentNodeEntity(UUID id, String code, String name) {
    this.id = id;
    this.code = code;
    this.name = name;
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
}
