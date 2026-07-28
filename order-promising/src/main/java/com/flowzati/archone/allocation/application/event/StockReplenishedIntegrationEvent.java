package com.flowzati.archone.allocation.application.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.flowzati.archone.common.integration.IntegrationEvent;
import java.util.UUID;

/**
 * 外部 Inventory bounded context 的補貨事實。
 *
 * <p>帶 {@code ownerId} 是因為補貨要喚醒的是<strong>某個貨主</strong>的缺貨佇列——SKU 代碼由
 * 貨主自訂、跨貨主撞號，只憑 SKU 無法決定該喚醒誰的訂單。
 *
 * <p>已知的中間狀態：庫存本身尚未按貨主分開（{@code stock_pools} 還沒有 {@code owner_id}），
 * 因此補進去的量兩個貨主都吃得到。**佇列分開了，庫存還沒分開。**
 */
public final class StockReplenishedIntegrationEvent extends IntegrationEvent {
  private final UUID ownerId;
  private final String sku;
  private final int quantity;

  @JsonCreator
  public StockReplenishedIntegrationEvent(
      @JsonProperty("eventId") UUID eventId,
      @JsonProperty("ownerId") UUID ownerId,
      @JsonProperty("sku") String sku,
      @JsonProperty("quantity") int quantity
  ) {
    super(eventId);
    if (ownerId == null) {
      throw new IllegalArgumentException("Owner ID is required");
    }
    if (sku == null || sku.isBlank()) {
      throw new IllegalArgumentException("SKU is required");
    }
    if (quantity <= 0) {
      throw new IllegalArgumentException("Replenishment quantity must be positive");
    }
    this.ownerId = ownerId;
    this.sku = sku;
    this.quantity = quantity;
  }

  public UUID getOwnerId() {
    return ownerId;
  }

  public String getSku() {
    return sku;
  }

  public int getQuantity() {
    return quantity;
  }
}
