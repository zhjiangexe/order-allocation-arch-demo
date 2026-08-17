package com.flowzati.archone.stock.infrastructure.messaging.producer;

import com.flowzati.archone.contracts.inventory.v1.StockAvailabilityIncreasedIntegrationEvent;
import com.flowzati.archone.contracts.fulfillment.v1.AllocationCommittedForFulfillmentIntegrationEvent;
import com.flowzati.archone.contracts.promising.v1.OrderAllocatedIntegrationEvent;
import com.flowzati.archone.foundation.identity.IdGenerator;
import com.flowzati.archone.messaging.events.AggregateReference;
import com.flowzati.archone.messaging.events.IntegrationEventPublisher;
import com.flowzati.archone.messaging.events.PublicationTarget;
import com.flowzati.archone.promising.domain.DomainEvent;
import com.flowzati.archone.promising.messaging.OutboxAggregateTypes;
import com.flowzati.archone.promising.messaging.StockContentionKey;
import com.flowzati.archone.stock.application.event.AllocationDomainEventPublisher;
import com.flowzati.archone.stock.application.event.InventoryEventTopics;
import com.flowzati.archone.stock.application.event.FulfillmentEventTopics;
import com.flowzati.archone.stock.application.event.PromisingEventTopics;
import com.flowzati.archone.stock.domain.event.OrderAllocationCompleted;
import com.flowzati.archone.stock.domain.event.StockAvailabilityIncreased;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Producer-side adapter from Allocation domain facts to external Integration Events.
 * It delegates reliable publication to {@link IntegrationEventPublisher}, whose runtime producer
 * is Outbox.
 */
@Component
public class AllocationIntegrationEventPublisher implements AllocationDomainEventPublisher {

  private final IntegrationEventPublisher eventPublisher;

  public AllocationIntegrationEventPublisher(IntegrationEventPublisher eventPublisher) {
    this.eventPublisher = eventPublisher;
  }

  @Override
  public void publish(DomainEvent event) {
    if (event instanceof OrderAllocationCompleted completed) {
      translate(completed);
      return;
    }
    if (event instanceof StockAvailabilityIncreased availabilityIncreased) {
      translate(availabilityIncreased);
      return;
    }
    throw new IllegalArgumentException(
        "Unsupported allocation domain event: " + event.getClass().getName());
  }

  public void translate(OrderAllocationCompleted event) {
    OrderAllocatedIntegrationEvent integration = new OrderAllocatedIntegrationEvent(
        IdGenerator.nextId(),
        event.orderId(),
        event.allocatedAt());
    eventPublisher.publish(
        integration,
        new AggregateReference(OutboxAggregateTypes.ORDER, event.orderId().toString()),
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
            OutboxAggregateTypes.STOCK_PICKING, event.allocationId().toString()),
        new PublicationTarget(
            FulfillmentEventTopics.FULFILLMENT_HANDOFFS, event.orderId().toString()),
        event.allocatedAt());
  }

  /** Receipt completion and its availability notification commit through the same Outbox. */
  public void translate(StockAvailabilityIncreased event) {
    String contentionKey = StockContentionKey.of(event.ownerId(), event.facilityId());
    eventPublisher.publish(
        new StockAvailabilityIncreasedIntegrationEvent(
            IdGenerator.nextId(), event.ownerId(), event.facilityId(), event.locationId(),
            event.skuCode(), event.quantity()),
        new AggregateReference(OutboxAggregateTypes.STOCK_POOL, contentionKey),
        new PublicationTarget(InventoryEventTopics.STOCK_EVENTS, contentionKey),
        event.occurredAt());
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
    return new PublicationTarget(PromisingEventTopics.ALLOCATION_EVENTS, orderId.toString());
  }
}
