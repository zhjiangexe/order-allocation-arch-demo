package com.flowzati.archone.allocation.application.event.translator;

import com.flowzati.archone.allocation.application.event.BackorderCreatedIntegrationEvent;
import com.flowzati.archone.allocation.application.event.OrderAllocatedIntegrationEvent;
import com.flowzati.archone.allocation.domain.event.OrderAllocationCompleted;
import com.flowzati.archone.common.IdGenerator;
import com.flowzati.archone.common.outbox.OutboxAppender;
import com.flowzati.archone.common.outbox.OutboxAggregateTypes;
import com.flowzati.archone.common.outbox.OutboxDelivery;
import com.flowzati.archone.common.messaging.IntegrationEventTopics;
import com.flowzati.archone.ordering.domain.event.LineSnapshot;
import com.flowzati.archone.ordering.domain.event.OrderBackordered;
import com.flowzati.archone.ordering.domain.event.OrderPlaced;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class AllocationDomainEventTranslator {

  private final OutboxAppender outboxAppender;

  public AllocationDomainEventTranslator(OutboxAppender outboxAppender) {
    this.outboxAppender = outboxAppender;
  }

  @EventListener
  public void translate(OrderAllocationCompleted event) {
    outboxAppender.append(
        new OrderAllocatedIntegrationEvent(
            IdGenerator.nextId(),
            event.orderId(),
            event.reservationId(),
            event.sku(),
            event.quantity(),
            event.allocatedAt()),
        OutboxAggregateTypes.ORDER,
        event.orderId().toString(),
        deliveryKeyedByOrder(event.orderId()),
        event.allocatedAt()
    );
  }

  @EventListener
  public void translate(OrderBackordered event) {
    // 同 OrderPlaced:此 integration event 的契約仍是單一 SKU 與數量,摺疊經過具名方法。
    String skuCode = LineSnapshot.requireSingleSku(event.lines());
    outboxAppender.append(
        new BackorderCreatedIntegrationEvent(
            IdGenerator.nextId(),
            event.orderId(),
            skuCode,
            LineSnapshot.totalQuantity(event.lines()),
            event.backorderedSince()),
        OutboxAggregateTypes.ORDER,
        event.orderId().toString(),
        deliveryKeyedByOrder(event.orderId()),
        event.backorderedSince()
    );
  }

  /**
   * 配置結果事件一律以 orderId 當 partition key，不套用
   * {@code archone.allocation.partition-key-strategy}。
   *
   * <p>sku 策略存在的目的，是讓同一個 SKU 的下單事件收斂進同一個 partition，使
   * allocation consumer 成為該 SKU 的 single writer。{@code promising.allocation-events}
   * 在本 repo 沒有任何 consumer，沒有需要被保護的寫入端；為了「一致性」把策略套上來，
   * 會製造一個無人驗證、無人受益的行為分支。要改動這裡，先確認該 topic 已經有
   * consumer，而且它確實需要 per-SKU 的順序保證。
   */
  private static OutboxDelivery deliveryKeyedByOrder(UUID orderId) {
    return new OutboxDelivery(
        IntegrationEventTopics.PROMISING_ALLOCATION_EVENTS_TOPIC, orderId.toString());
  }
}
