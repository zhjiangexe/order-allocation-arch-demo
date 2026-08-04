package com.flowzati.archone.catalog.domain.model;

import java.util.UUID;

/**
 * 物流作業設施：負責庫存、搬運或履約工作的實體場站。
 *
 * <p>刻意只有身分，沒有屬性。沒有狀態、類型、覆蓋範圍、處理能力、產能或截單時間——那些
 * 全都是為了「系統選點」而存在，而本系統不做那件事：3PL 的履約設施由合約決定，貨主在上游
 * 下單時就指定了。它目前可以是倉庫、配送中心或 cross-dock，不等同於設施內的庫存位置。
 *
 * <p>主檔由 seed 建立，不提供寫入介面，因此沒有行為方法。
 */
public class Facility {

  private final UUID id;
  private final String code;
  private final String name;

  public Facility(UUID id, String code, String name) {
    if (id == null) {
      throw new IllegalArgumentException("Facility ID is required");
    }
    if (code == null || code.isBlank()) {
      throw new IllegalArgumentException("Facility code is required");
    }
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("Facility name is required");
    }
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
