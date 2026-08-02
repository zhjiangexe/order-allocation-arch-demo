package com.flowzati.archone.stock.application.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.flowzati.archone.common.integration.IntegrationEvent;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 外部 Inventory bounded context 的補貨事實。
 *
 * <p>五個維度都必須帶：貨主、倉、SKU、入庫日、效期。它們合起來決定這批貨要加到哪一列——命中
 * 既有列就加數量，否則新開一列。少任何一個都得定義合併規則，而任何一條規則都會在某些情況下
 * 把不可互換的貨併在一起。
 *
 * <p>{@code ownerId} 同時決定要喚醒哪一個貨主的缺貨佇列：SKU 代碼由貨主自訂、跨貨主撞號，
 * 只憑 SKU 無法決定該喚醒誰的訂單。
 */
public final class StockReplenishedIntegrationEvent extends IntegrationEvent {
  private final UUID ownerId;
  private final UUID nodeId;
  private final String sku;
  private final LocalDate inDate;
  private final LocalDate expiryDate;
  private final int quantity;

  @JsonCreator
  public StockReplenishedIntegrationEvent(
      @JsonProperty("eventId") UUID eventId,
      @JsonProperty("ownerId") UUID ownerId,
      @JsonProperty("nodeId") UUID nodeId,
      @JsonProperty("sku") String sku,
      @JsonProperty("inDate") LocalDate inDate,
      @JsonProperty("expiryDate") LocalDate expiryDate,
      @JsonProperty("quantity") int quantity
  ) {
    super(eventId);
    if (ownerId == null) {
      throw new IllegalArgumentException("Owner ID is required");
    }
    if (nodeId == null) {
      throw new IllegalArgumentException("Fulfillment node ID is required");
    }
    if (sku == null || sku.isBlank()) {
      throw new IllegalArgumentException("SKU is required");
    }
    if (inDate == null) {
      throw new IllegalArgumentException("In-date is required");
    }
    if (expiryDate == null) {
      throw new IllegalArgumentException("Expiry date is required");
    }
    if (quantity <= 0) {
      throw new IllegalArgumentException("Replenishment quantity must be positive");
    }
    this.ownerId = ownerId;
    this.nodeId = nodeId;
    this.sku = sku;
    this.inDate = inDate;
    this.expiryDate = expiryDate;
    this.quantity = quantity;
  }

  public UUID getOwnerId() {
    return ownerId;
  }

  public UUID getNodeId() {
    return nodeId;
  }

  public String getSku() {
    return sku;
  }

  public LocalDate getInDate() {
    return inDate;
  }

  public LocalDate getExpiryDate() {
    return expiryDate;
  }

  public int getQuantity() {
    return quantity;
  }
}
