package com.flowzati.archone.stock.entrypoint.messaging;

import com.flowzati.archone.contracts.inventory.v1.StockAvailabilityIncreasedIntegrationEvent;
import com.flowzati.archone.messaging.autoconfigure.ConditionalOnIntegrationEventConsumption;
import com.flowzati.archone.messaging.events.IntegrationEventDispatcher;
import com.flowzati.archone.messaging.events.IntegrationEventDispatcherFactory;
import com.flowzati.archone.messaging.events.IntegrationEventHandlers;
import com.flowzati.archone.messaging.events.IntegrationEventHandlersBuilder;
import com.flowzati.archone.stock.application.command.AllocateWaitingDemandCommand;
import com.flowzati.archone.stock.application.event.AllocationEventSubscriptions;
import com.flowzati.archone.stock.application.event.InventoryEventTopics;
import com.flowzati.archone.stock.application.usecase.AllocateWaitingDemandUsecase;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Tram-style consumer for physical stock availability facts handled by Allocation. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnIntegrationEventConsumption
public class AllocationInventoryAvailabilityEventConsumer {

  private final AllocateWaitingDemandUsecase allocateWaitingDemandUsecase;

  public AllocationInventoryAvailabilityEventConsumer(AllocateWaitingDemandUsecase allocateWaitingDemandUsecase) {
    this.allocateWaitingDemandUsecase = allocateWaitingDemandUsecase;
  }

  @Bean
  IntegrationEventDispatcher allocationInventoryAvailabilityIntegrationEventDispatcher(
      IntegrationEventDispatcherFactory factory
  ) {
    IntegrationEventHandlers handlers = IntegrationEventHandlersBuilder
        .forDestination(InventoryEventTopics.STOCK_EVENTS)
        .onEvent(StockAvailabilityIncreasedIntegrationEvent.class,
            envelope -> onStockAvailabilityIncreased(envelope.event()))
        .build();
    return factory.make(AllocationEventSubscriptions.INVENTORY_AVAILABILITY, handlers);
  }

  void onStockAvailabilityIncreased(StockAvailabilityIncreasedIntegrationEvent event) {
    allocateWaitingDemandUsecase.execute(new AllocateWaitingDemandCommand(
        event.getOwnerId(), event.getFacilityId(), event.getLocationId(), event.getSku()));
  }
}
