package com.flowzati.archone.stock.entrypoint.messaging;

import com.flowzati.archone.contracts.ordering.v1.OrderCancelledIntegrationEvent;
import com.flowzati.archone.contracts.ordering.v1.OrderPlacedIntegrationEvent;
import com.flowzati.archone.messaging.api.MessageSubscriptionOptions;
import com.flowzati.archone.messaging.autoconfigure.ConditionalOnIntegrationEventConsumption;
import com.flowzati.archone.messaging.events.IntegrationEventDispatcher;
import com.flowzati.archone.messaging.events.IntegrationEventDispatcherFactory;
import com.flowzati.archone.messaging.events.IntegrationEventDispatcherOptions;
import com.flowzati.archone.messaging.events.IntegrationEventHandlers;
import com.flowzati.archone.messaging.events.IntegrationEventHandlersBuilder;
import com.flowzati.archone.messaging.events.UnhandledIntegrationEventObserver;
import com.flowzati.archone.ordering.application.event.OrderingEventTopics;
import com.flowzati.archone.stock.application.event.AllocationEventSubscriptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Allocation subscription for Order lifecycle facts. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnIntegrationEventConsumption
public class AllocationOrderLifecycleEventConfiguration {

  public static final String ALLOCATION_ORDER_LIFECYCLE_HANDLERS =
      "allocationOrderLifecycleIntegrationEventHandlers";

  @Bean(ALLOCATION_ORDER_LIFECYCLE_HANDLERS)
  IntegrationEventHandlers allocationOrderLifecycleIntegrationEventHandlers(
      AllocationOrderLifecycleEventTarget target
  ) {
    return IntegrationEventHandlersBuilder
        .forDestination(OrderingEventTopics.ORDER_EVENTS)
        .onEvent(
            OrderPlacedIntegrationEvent.class,
            envelope -> target.onOrderPlaced(envelope.event()))
        .onEvent(
            OrderCancelledIntegrationEvent.class,
            envelope -> target.onOrderCancelled(envelope.event()))
        .build();
  }

  @Bean
  IntegrationEventDispatcher allocationOrderLifecycleIntegrationEventDispatcher(
      IntegrationEventDispatcherFactory factory,
      @Qualifier(ALLOCATION_ORDER_LIFECYCLE_HANDLERS) IntegrationEventHandlers handlers,
      UnhandledIntegrationEventObserver observer
  ) {
    return factory.make(
        AllocationEventSubscriptions.ORDER_LIFECYCLE,
        handlers,
        IntegrationEventDispatcherOptions.builder()
            .subscriptionOptions(MessageSubscriptionOptions.withConsumerGroupId(
                AllocationEventSubscriptions.ORDER_LIFECYCLE_CONSUMER_GROUP))
            .ignoreUnhandledEventsWith(observer)
            .build());
  }
}
