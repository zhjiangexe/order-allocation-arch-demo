package com.flowzati.archone.stock.application.event;

import com.flowzati.archone.stock.application.source.order.OrderAllocationCompletionAdapter;
import com.flowzati.archone.stock.domain.event.AllocationCommitted;
import com.flowzati.archone.stock.domain.event.OrderAllocationCompleted;
import com.flowzati.archone.stock.domain.model.AllocationSourceType;
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
  private final AllocationDomainEventPublisher publisher;

  public AllocationCompletionRouter(
      OrderAllocationCompletionAdapter orderAdapter,
      AllocationDomainEventPublisher publisher) {
    this.orderAdapter = orderAdapter;
    this.publisher = publisher;
  }

  public void publish(AllocationCommitted fact) {
    if (fact.source().sourceType() == AllocationSourceType.ORDER) {
      OrderAllocationCompleted translate = orderAdapter.translate(fact);
      publisher.publish(translate);
      return;
    }
    throw new IllegalStateException("No production completion adapter is enabled for " + fact.source().sourceType());
  }
}
