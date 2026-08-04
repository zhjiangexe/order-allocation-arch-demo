package com.flowzati.archone.stock.application.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.flowzati.archone.common.integration.IntegrationEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * 這張單配不到貨,進了缺貨佇列。
 *
 * <p><b>只帶識別與時間,不帶任何需求內容。</b>消費端拿 {@code orderId} 回頭讀整張單——它反正
 * 得讀（收件資訊、交期、行的內容都不在事件裡）,payload 抄一份只是多一個會與訂單不一致的來源。
 *
 * <p><b>連 {@code ownerId} 與 {@code facilityId} 都不帶。</b>它們不變、也是每張單單值,曾經以「消費端
 * 要能不查就過濾」為理由放進來——但這個 repo 裡**沒有任何按貨主或按倉過濾的消費端**,那個理由
 * 是為想像中的下游設計。哪天真的出現了再加:**加欄位對消費端是非破壞性的,砍欄位不是**,所以
 * 起點該是最小。
 *
 * <p>例外是**來自系統外部**的事件（{@code StockReplenishedIntegrationEvent}）:那裡沒有本地
 * 聚合根可讀,事實只存在於訊息裡,所以必須帶。
 *
 * <p>partition key 需要貨主、倉與 SKU,但那是**傳遞決策**,寫在 outbox 的 {@code partition_key}
 * 欄位而不是 payload（見 {@code outbox-event-delivery} 規格:分區策略不得影響 payload content）。
 * translator 從**領域**事件取那些值,領域事件是 in-process 的,帶著它們沒有契約成本。
 */
public final class BackorderCreatedIntegrationEvent extends IntegrationEvent {
  private final UUID orderId;
  private final Instant backorderedSince;

  @JsonCreator
  public BackorderCreatedIntegrationEvent(
      @JsonProperty("eventId") UUID eventId,
      @JsonProperty("orderId") UUID orderId,
      @JsonProperty("backorderedSince") Instant backorderedSince
  ) {
    super(eventId);
    if (orderId == null) {
      throw new IllegalArgumentException("Order ID is required");
    }
    if (backorderedSince == null) {
      throw new IllegalArgumentException("Backordered time is required");
    }
    this.orderId = orderId;
    this.backorderedSince = backorderedSince;
  }

  public UUID getOrderId() {
    return orderId;
  }

  public Instant getBackorderedSince() {
    return backorderedSince;
  }
}
