package com.flowzati.archone.contracts.ordering.v1;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.flowzati.archone.messaging.events.IntegrationEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * 上游收了一張單。
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
 *
 * <p>時間戳是 {@code receivedAt}——**我們收到這張單的時刻**。它曾經叫 {@code placedAt}，而那個
 * 名字現在指的是另一件事（上游說客戶下單的時刻，可能不存在）。事件帶的一律是系統事實：消費端
 * 問的是「這件事什麼時候發生」，而不是「客戶什麼時候按下送出」。
 */
public final class OrderPlacedIntegrationEvent extends IntegrationEvent {

    public static final String EVENT_TYPE = "OrderPlacedIntegrationEvent";
    public static final int CONTRACT_VERSION = 1;

    private final UUID orderId;
    private final Instant receivedAt;

    @JsonCreator
    public OrderPlacedIntegrationEvent(
            @JsonProperty("eventId") UUID eventId,
            @JsonProperty("orderId") UUID orderId,
            @JsonProperty("receivedAt") Instant receivedAt) {
        super(eventId);
        if (orderId == null) {
            throw new IllegalArgumentException("Order ID is required");
        }
        if (receivedAt == null) {
            throw new IllegalArgumentException("Received time is required");
        }
        this.orderId = orderId;
        this.receivedAt = receivedAt;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    @Override
    public String eventType() {
        return EVENT_TYPE;
    }
}
