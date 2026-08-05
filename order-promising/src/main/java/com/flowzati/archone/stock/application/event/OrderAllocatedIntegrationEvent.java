package com.flowzati.archone.stock.application.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.flowzati.archone.common.integration.IntegrationEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * 這張單配到貨了。
 *
 * <p><b>只帶識別與時間,不帶任何需求內容。</b>消費端拿 {@code orderId} 回頭讀整張單——它反正
 * 得讀（收件資訊、交期、行的內容都不在事件裡）,payload 抄一份只是多一個會與訂單不一致的來源。
 *
 * <p><b>連 {@code ownerId} 與 {@code facilityId} 都不帶。</b>它們不變、也是每張單單值,曾經以「消費端
 * 要能不查就過濾」為理由放進來——但這個 repo 裡**沒有任何按貨主或按倉過濾的消費端**,那個理由
 * 是為想像中的下游設計。哪天真的出現了再加:**加欄位對消費端是非破壞性的,砍欄位不是**,所以
 * 起點該是最小。
 *
 * <p>partition key 需要貨主、倉與 SKU,但那是**傳遞決策**,寫在 outbox 的 {@code partition_key}
 * 欄位而不是 payload（見 {@code outbox-event-delivery} 規格:分區策略不得影響 payload content）。
 * translator 從**領域**事件取那些值,領域事件是 in-process 的,帶著它們沒有契約成本。
 */
public final class OrderAllocatedIntegrationEvent extends IntegrationEvent {
  private final UUID orderId;
  private final Instant allocatedAt;

  @JsonCreator
  public OrderAllocatedIntegrationEvent(
      @JsonProperty("eventId") UUID eventId,
      @JsonProperty("orderId") UUID orderId,
      @JsonProperty("allocatedAt") Instant allocatedAt
  ) {
    super(eventId);
    if (orderId == null) {
      throw new IllegalArgumentException("Order ID is required");
    }
    if (allocatedAt == null) {
      throw new IllegalArgumentException("Allocated time is required");
    }
    this.orderId = orderId;
    this.allocatedAt = allocatedAt;
  }

  public UUID getOrderId() {
    return orderId;
  }

  public Instant getAllocatedAt() {
    return allocatedAt;
  }
}
