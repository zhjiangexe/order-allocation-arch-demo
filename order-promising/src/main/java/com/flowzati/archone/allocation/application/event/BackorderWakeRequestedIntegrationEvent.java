package com.flowzati.archone.allocation.application.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.flowzati.archone.common.integration.IntegrationEvent;
import java.util.UUID;

/**
 * 續做喚醒：上一輪喚醒到達批次上限，佇列可能還有單，請接著處理。
 *
 * <p>發往<b>同一個 topic、同一個 partition key</b>。這不是形式上的一致：它要處理的是與補貨
 * 事件同一個 {@code (貨主, 倉, SKU)} 的庫存，落到別的 partition 就會與正在處理的補貨並行，
 * 而 single writer 的保證正是靠同 key 取得的。
 *
 * <p>經 outbox 而不是直接發往 Kafka——續做與這一輪的喚醒必須同進退。直接發的話，交易回滾後
 * 續做事件仍然送出去了，下游會為一輪從未發生的喚醒繼續做。
 */
public final class BackorderWakeRequestedIntegrationEvent extends IntegrationEvent {
  private final UUID ownerId;
  private final UUID nodeId;
  private final String sku;

  @JsonCreator
  public BackorderWakeRequestedIntegrationEvent(
      @JsonProperty("eventId") UUID eventId,
      @JsonProperty("ownerId") UUID ownerId,
      @JsonProperty("nodeId") UUID nodeId,
      @JsonProperty("sku") String sku
  ) {
    super(eventId);
    if (ownerId == null || nodeId == null) {
      throw new IllegalArgumentException("Owner ID and node ID are required");
    }
    if (sku == null || sku.isBlank()) {
      throw new IllegalArgumentException("SKU is required");
    }
    this.ownerId = ownerId;
    this.nodeId = nodeId;
    this.sku = sku;
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
}
