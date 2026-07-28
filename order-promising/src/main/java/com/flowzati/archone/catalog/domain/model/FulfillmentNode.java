package com.flowzati.archone.catalog.domain.model;

import java.util.UUID;

/**
 * 倉庫：貨從這裡出。
 *
 * <p>刻意只有身分，沒有屬性。沒有狀態、類型、覆蓋範圍、處理能力、產能或截單時間——那些
 * 全都是為了「系統選倉」而存在，而本系統不做那件事：3PL 的出貨倉由合約決定，貨主在上游
 * 下單時就指定了。加一個沒有讀者的欄位比缺一個糟，因為下一個人會假設它有意義。
 *
 * <p>主檔由 seed 建立，不提供寫入介面，因此沒有行為方法。
 */
public class FulfillmentNode {

  private final UUID id;
  private final String code;
  private final String name;

  public FulfillmentNode(UUID id, String code, String name) {
    if (id == null) {
      throw new IllegalArgumentException("Fulfillment node ID is required");
    }
    if (code == null || code.isBlank()) {
      throw new IllegalArgumentException("Fulfillment node code is required");
    }
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("Fulfillment node name is required");
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
