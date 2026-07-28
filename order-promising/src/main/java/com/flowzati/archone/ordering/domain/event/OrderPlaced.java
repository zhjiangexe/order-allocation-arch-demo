package com.flowzati.archone.ordering.domain.event;

import com.flowzati.archone.common.ddd.DomainEvent;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 收單。
 *
 * <p>帶 {@code ownerId} 是因為下游不該為了知道「這批貨屬於誰」而回頭查訂單；帶
 * {@code shipToZone} 與 {@code promisedDeliveryDate} 是 R6 選點的決策輸入。
 *
 * <p>需求以 {@code lines} 表達而不是單一 SKU 與數量——訂單的形狀本來就是行的集合，事件
 * 沿用同一個形狀，下游不需要知道「目前每張單只有一行」這件事。
 */
public record OrderPlaced(
    UUID orderId,
    UUID ownerId,
    String shipToZone,
    LocalDate promisedDeliveryDate,
    List<LineSnapshot> lines,
    Instant placedAt
) implements DomainEvent {
}
