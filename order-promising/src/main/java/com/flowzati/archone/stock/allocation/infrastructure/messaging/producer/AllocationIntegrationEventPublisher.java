package com.flowzati.archone.stock.allocation.infrastructure.messaging.producer;

import com.flowzati.archone.contracts.fulfillment.v1.AllocationCommittedForFulfillmentIntegrationEvent;
import com.flowzati.archone.contracts.fulfillment.v1.FulfillmentAggregateTypes;
import com.flowzati.archone.contracts.fulfillment.v1.FulfillmentChannels;
import com.flowzati.archone.contracts.ordering.v1.OrderingAggregateTypes;
import com.flowzati.archone.contracts.promising.v1.AllocationChannels;
import com.flowzati.archone.contracts.promising.v1.OrderAllocatedIntegrationEvent;
import com.flowzati.archone.foundation.identity.IdGenerator;
import com.flowzati.archone.messaging.events.AggregateReference;
import com.flowzati.archone.messaging.events.IntegrationEventPublisher;
import com.flowzati.archone.messaging.events.PublicationTarget;
import com.flowzati.archone.stock.allocation.application.event.AllocationEventPublisher;
import com.flowzati.archone.stock.allocation.domain.event.OrderAllocationCompleted;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Producer-side adapter from Allocation domain facts to external Integration Events.
 * It delegates reliable publication to {@link IntegrationEventPublisher}, whose runtime producer
 * is Outbox.
 */
@Component
public class AllocationIntegrationEventPublisher implements AllocationEventPublisher {

  private final IntegrationEventPublisher eventPublisher;

  public AllocationIntegrationEventPublisher(IntegrationEventPublisher eventPublisher) {
    this.eventPublisher = eventPublisher;
  }

  @Override
  public void publish(OrderAllocationCompleted event) {
    translate(event);
  }

  public void translate(OrderAllocationCompleted event) {
    OrderAllocatedIntegrationEvent integration = new OrderAllocatedIntegrationEvent(
        IdGenerator.nextId(),
        event.orderId(),
        event.allocatedAt());
    eventPublisher.publish(
        integration,
        new AggregateReference(OrderingAggregateTypes.ORDER, event.orderId().toString()),
        deliveryKeyedByOrder(event.orderId()),
        event.allocatedAt()
    );
    AllocationCommittedForFulfillmentIntegrationEvent fulfillment =
        new AllocationCommittedForFulfillmentIntegrationEvent(
            IdGenerator.nextId(),
            event.allocationId(),
            event.orderId(),
            event.ownerId(),
            event.facilityId(),
            event.lines().stream()
                .map(line -> new AllocationCommittedForFulfillmentIntegrationEvent.AllocationLine(
                    line.orderLineId(), line.moveId(), line.skuCode(),
                    line.sourceLocationId(), line.quantity()))
                .toList(),
            event.dispatchBy(),
            event.releasePriority(),
            event.allocatedAt());
    eventPublisher.publish(
        fulfillment,
        new AggregateReference(
            FulfillmentAggregateTypes.STOCK_PICKING, event.allocationId().toString()),
        new PublicationTarget(
            FulfillmentChannels.FULFILLMENT_HANDOFFS, event.orderId().toString()),
        event.allocatedAt());
  }

  /**
   * 配置結果事件一律以 orderId 當 partition key，不套用
   * {@code archone.allocation.partition-key-strategy}。
   *
   * <p>{@code stock} 策略存在的目的，是讓會搶同一批庫存的下單事件收斂進同一個 partition，使
   * allocation consumer 成為那些庫存列的 single writer。{@code promising.allocation-events}
   * 在本 repo 沒有任何 consumer，沒有需要被保護的寫入端；為了「一致性」把策略套上來，
   * 會製造一個無人驗證、無人受益的行為分支。要改動這裡，先確認該 topic 已經有
   * consumer，而且它確實需要庫存維度的順序保證。
   */
  private static PublicationTarget deliveryKeyedByOrder(UUID orderId) {
    return new PublicationTarget(AllocationChannels.ALLOCATION_EVENTS, orderId.toString());
  }
}
