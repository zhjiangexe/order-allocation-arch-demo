package com.flowzati.archone.inventory.balance.infrastructure.messaging.producer;

import com.flowzati.archone.contracts.inventory.v1.InventoryAggregateTypes;
import com.flowzati.archone.contracts.inventory.v1.InventoryChannels;
import com.flowzati.archone.contracts.inventory.v1.StockAvailabilityIncreasedIntegrationEvent;
import com.flowzati.archone.contracts.stock.v1.StockContentionKey;
import com.flowzati.archone.foundation.identity.IdGenerator;
import com.flowzati.archone.messaging.events.AggregateReference;
import com.flowzati.archone.messaging.events.IntegrationEventPublisher;
import com.flowzati.archone.messaging.events.PublicationTarget;
import com.flowzati.archone.inventory.balance.application.event.InventoryEventPublisher;
import com.flowzati.archone.inventory.balance.domain.event.StockAvailabilityIncreased;
import org.springframework.stereotype.Component;

/** 將庫存領域事實轉譯成 inventory Integration Event，並交由 Outbox 可靠發布。 */
@Component
public class InventoryIntegrationEventPublisher implements InventoryEventPublisher {

  private final IntegrationEventPublisher eventPublisher;

  public InventoryIntegrationEventPublisher(IntegrationEventPublisher eventPublisher) {
    this.eventPublisher = eventPublisher;
  }

  @Override
  public void publish(StockAvailabilityIncreased event) {
    String contentionKey = StockContentionKey.of(event.ownerId(), event.facilityId());
    eventPublisher.publish(
        new StockAvailabilityIncreasedIntegrationEvent(
            IdGenerator.nextId(), event.ownerId(), event.facilityId(), event.locationId(),
            event.skuCode(), event.quantity()),
        new AggregateReference(InventoryAggregateTypes.STOCK_POOL, contentionKey),
        new PublicationTarget(InventoryChannels.STOCK_EVENTS, contentionKey),
        event.occurredAt());
  }
}
