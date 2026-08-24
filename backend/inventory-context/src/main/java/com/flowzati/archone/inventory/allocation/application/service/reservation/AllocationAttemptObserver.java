package com.flowzati.archone.inventory.allocation.application.service.reservation;

import com.flowzati.archone.inventory.allocation.domain.valueobject.PendingDemandQueuePosition;
import java.time.Instant;

/**
 * 配貨嘗試的觀測 port。
 *
 * <p>Application 只描述需要留下的業務觀測事實；metrics 與 log 的實作由 infrastructure 提供。
 */
public interface AllocationAttemptObserver {

    void recordBlocked(PendingDemandQueuePosition position, Instant observedAt);
}
