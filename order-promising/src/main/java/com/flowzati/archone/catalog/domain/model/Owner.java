package com.flowzati.archone.catalog.domain.model;

import java.util.UUID;

/**
 * 貨主：委託本倉儲存與出貨的一方。倉庫不擁有貨，貨屬於委託方。
 *
 * <p>本 change 沒有行為方法——主檔由 seed 建立，不提供寫入介面。
 */
public class Owner {

  private final UUID id;
  private final String code;
  private final String name;
  private final OwnerStatus status;
  /**
   * 是否允許一張單跨節點拆單。這是 3PL 簽約時定的服務條款，逐貨主決定一次，不是逐單
   * 決定，因此存在貨主主檔而非訂單上。本 change 只存下來，R6 才讀。
   */
  private final boolean allowSplitShipment;

  public Owner(
      UUID id,
      String code,
      String name,
      OwnerStatus status,
      boolean allowSplitShipment
  ) {
    if (id == null) {
      throw new IllegalArgumentException("Owner ID is required");
    }
    if (code == null || code.isBlank()) {
      throw new IllegalArgumentException("Owner code is required");
    }
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("Owner name is required");
    }
    if (status == null) {
      throw new IllegalArgumentException("Owner status is required");
    }
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

  public boolean allowsSplitShipment() {
    return allowSplitShipment;
  }
}
