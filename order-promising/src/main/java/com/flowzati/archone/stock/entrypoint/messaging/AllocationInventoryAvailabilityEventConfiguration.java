package com.flowzati.archone.stock.entrypoint.messaging;

import com.flowzati.archone.contracts.inventory.v1.StockAvailabilityIncreasedIntegrationEvent;
import com.flowzati.archone.messaging.api.MessageSubscriptionOptions;
import com.flowzati.archone.messaging.autoconfigure.ConditionalOnIntegrationEventConsumption;
import com.flowzati.archone.messaging.events.IntegrationEventDispatcher;
import com.flowzati.archone.messaging.events.IntegrationEventDispatcherFactory;
import com.flowzati.archone.messaging.events.IntegrationEventDispatcherOptions;
import com.flowzati.archone.messaging.events.IntegrationEventHandlers;
import com.flowzati.archone.messaging.events.IntegrationEventHandlersBuilder;
import com.flowzati.archone.messaging.events.UnhandledIntegrationEventObserver;
import com.flowzati.archone.stock.application.event.AllocationEventSubscriptions;
import com.flowzati.archone.stock.application.event.InventoryEventTopics;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Allocation subscription for physical stock availability facts. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnIntegrationEventConsumption
public class AllocationInventoryAvailabilityEventConfiguration {

  public static final String ALLOCATION_INVENTORY_AVAILABILITY_HANDLERS =
      "allocationInventoryAvailabilityIntegrationEventHandlers";

  @Bean(ALLOCATION_INVENTORY_AVAILABILITY_HANDLERS)
  IntegrationEventHandlers allocationInventoryAvailabilityIntegrationEventHandlers(
      AllocationInventoryAvailabilityEventTarget target
  ) {
    return IntegrationEventHandlersBuilder
        .forDestination(InventoryEventTopics.STOCK_EVENTS)
        .onEvent(
            StockAvailabilityIncreasedIntegrationEvent.class,
            envelope -> target.onStockAvailabilityIncreased(envelope.event()))
        .build();
  }

  @Bean
  IntegrationEventDispatcher allocationInventoryAvailabilityIntegrationEventDispatcher(
      IntegrationEventDispatcherFactory factory,
      @Qualifier(ALLOCATION_INVENTORY_AVAILABILITY_HANDLERS) IntegrationEventHandlers handlers,
      UnhandledIntegrationEventObserver observer
  ) {
    return factory.make(
        AllocationEventSubscriptions.INVENTORY_AVAILABILITY,
        handlers,
        IntegrationEventDispatcherOptions.builder()
            .subscriptionOptions(MessageSubscriptionOptions.withConsumerGroupId(
                AllocationEventSubscriptions.INVENTORY_AVAILABILITY_CONSUMER_GROUP))
            .ignoreUnhandledEventsWith(observer)
            .build());
  }
}
