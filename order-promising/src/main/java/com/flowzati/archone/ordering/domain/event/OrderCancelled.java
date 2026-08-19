package com.flowzati.archone.ordering.domain.event;

import com.flowzati.archone.foundation.domain.event.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * 取消是整單行為，所以帶的是整張單的識別而不是任何一條行。
 *
 * <p><b>不帶 {@code lines}。</b>曾經帶過——當時 partition key 含 SKU，translator 得從行摺出
 * 一個 SKU。key 改粗成 {@code (貨主, 倉)} 之後沒有任何讀取者，就移除了。下游要釋放的預留是
 * 整單的，而它從 {@code order_lines} 查得到，不需要事件轉述。
 *
 * <p>帶 {@code ownerId} 與 {@code facilityId} 是為了 partition key：取消要釋放的正是
 * 收單時鎖下的那些批，兩者的 key 必須算得出同一個值。少了倉別，取消事件會落在別的 partition，
 * 釋放與配貨就不再由同一個 writer 序列化。
 */
public record OrderCancelled(UUID orderId, UUID ownerId, UUID facilityId, Instant cancelledAt) implements DomainEvent {}
