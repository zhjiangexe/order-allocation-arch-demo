package com.flowzati.archone.stock.application.event;

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
/**
 * <b>這是我們發給自己的延續指令，不是給外部的事實。</b>
 *
 * <p>名字是 {@code Requested}（請你繼續做）而不是過去式的事實，那是刻意的——它由喚醒達到
 * 張數上限時產生，收它的也是本系統。對應的領域事件叫
 * {@code BackorderWakeContinuationRequired}：續做「被需要」是事實，「請求續做」是指令。
 *
 * <p><b>它與 {@link StockReplenishedIntegrationEvent} 共用 topic 與 partition key，那是刻意
 * 的。</b>兩者都以 {@code (貨主, 倉)} 當 key 走 {@code inventory.stock-events}——同 topic 同
 * key，Kafka 才保證順序。若把它移到「內部指令」專用的 topic，補貨與同一個 SKU 的續做會落在
 * 不同分割區、可能被併發處理，而那會弱化目前用來序列化喚醒的機制。
 *
 * <p>代價是這個 topic 上有兩種語意的訊息。今天沒有第三方訂閱它，所以只需要在這裡寫明；
 * 真的有外部消費者時，該做的是給它一個過濾條件，而不是搬家。
 */
public final class BackorderWakeRequestedIntegrationEvent extends IntegrationEvent {
  private final UUID ownerId;
  private final UUID facilityId;
  private final String sku;

  @JsonCreator
  public BackorderWakeRequestedIntegrationEvent(
      @JsonProperty("eventId") UUID eventId,
      @JsonProperty("ownerId") UUID ownerId,
      @JsonProperty("facilityId") UUID facilityId,
      @JsonProperty("sku") String sku
  ) {
    super(eventId);
    if (ownerId == null || facilityId == null) {
      throw new IllegalArgumentException("Owner ID and node ID are required");
    }
    if (sku == null || sku.isBlank()) {
      throw new IllegalArgumentException("SKU is required");
    }
    this.ownerId = ownerId;
    this.facilityId = facilityId;
    this.sku = sku;
  }

  public UUID getOwnerId() {
    return ownerId;
  }

  public UUID getFacilityId() {
    return facilityId;
  }

  public String getSku() {
    return sku;
  }
}
