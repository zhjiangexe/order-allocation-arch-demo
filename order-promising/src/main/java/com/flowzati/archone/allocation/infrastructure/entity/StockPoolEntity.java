package com.flowzati.archone.allocation.infrastructure.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

import jakarta.persistence.Version;

@Entity
public class StockPoolEntity {
  @Id
  private Long id;

  private String sku;

  private Integer available;

  @Version
  private Long version;

  public Long getId() {
    return id;
  }

  public String getSku() {
    return sku;
  }

  public Integer getAvailable() {
    return available;
  }

  public Long getVersion() {
    return version;
  }
}
