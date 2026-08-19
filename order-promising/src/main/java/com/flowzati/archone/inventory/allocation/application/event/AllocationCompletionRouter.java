package com.flowzati.archone.inventory.allocation.application.event;

import com.flowzati.archone.inventory.allocation.application.source.order.OrderAllocationCompletionAdapter;
import com.flowzati.archone.inventory.allocation.domain.event.AllocationCommitted;
import com.flowzati.archone.inventory.allocation.domain.event.OrderAllocationCompleted;
import com.flowzati.archone.inventory.allocation.domain.type.AllocationSourceType;
import org.springframework.stereotype.Component;

/**
 * 依 source type 把 generic allocation fact 交給對應 completion adapter。
 *
 * <p>Allocation core 到這裡仍不知道 order status 或 fulfillment contract；ORDER adapter 負責轉回
 * 既有 v1 event。尚未有 production adapter 的 source type 會明確失敗，不會靜默吞掉 completion。
 */
@Component
public class AllocationCompletionRouter {

    private final OrderAllocationCompletionAdapter orderAdapter;
    private final AllocationEventPublisher publisher;

    public AllocationCompletionRouter(
            OrderAllocationCompletionAdapter orderAdapter, AllocationEventPublisher publisher) {
        this.orderAdapter = orderAdapter;
        this.publisher = publisher;
    }

    public void publish(AllocationCommitted fact) {
        if (fact.source().sourceType() == AllocationSourceType.ORDER) {
            OrderAllocationCompleted translate = orderAdapter.translate(fact);
            publisher.publish(translate);
            return;
        }
        throw new IllegalStateException("No production completion adapter is enabled for "
                + fact.source().sourceType());
    }
}
