package com.flowzati.archone.ordering.domain.event;

import com.flowzati.archone.foundation.domain.event.DomainEvent;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 收單。
 *
 * <p>帶 {@code ownerId} 是因為下游不該為了知道「這批貨屬於誰」而回頭查訂單。
 *
 * <p>帶 {@code facilityId} 是為了 partition key：庫存分倉之後，會競爭同一批庫存的
 * 訊息由 {@code ownerId/facilityId/skuCode} 三者決定，translator 從這個事件取值，因此倉別必須
 * 在事件裡。現在加而不是等到需要時，是因為那時只要動 translator、不必改事件契約。
 *
 * <p>需求以 {@code lines} 表達而不是單一 SKU 與數量——訂單的形狀本來就是行的集合，事件
 * 沿用同一個形狀，下游不需要知道「目前每張單只有一行」這件事。
 *
 * <p>時間戳是 {@code receivedAt}——**我們收到這張單的時刻**，不是上游說客戶下單的時刻。事件
 * 描述的是「這件事在我們系統裡何時發生」；上游的下單時刻是訂單的屬性而非事件的屬性，需要它
 * 的消費端重讀訂單就拿得到，而且它可能根本不存在（上游沒有義務送）。
 */
public record OrderPlaced(
        UUID orderId,
        UUID ownerId,
        UUID facilityId,
        String shipToZone,
        LocalDate promisedDeliveryDate,
        List<LineSnapshot> lines,
        Instant receivedAt)
        implements DomainEvent {}
