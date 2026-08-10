package com.flowzati.archone.ordering.entrypoint.messaging;

import com.flowzati.archone.contracts.promising.v1.BackorderCreatedIntegrationEvent;
import com.flowzati.archone.contracts.promising.v1.OrderAllocatedIntegrationEvent;
import com.flowzati.archone.messaging.api.MessageSubscriptionOptions;
import com.flowzati.archone.messaging.autoconfigure.ConditionalOnIntegrationEventConsumption;
import com.flowzati.archone.messaging.events.IntegrationEventDispatcher;
import com.flowzati.archone.messaging.events.IntegrationEventDispatcherFactory;
import com.flowzati.archone.messaging.events.IntegrationEventHandlers;
import com.flowzati.archone.messaging.events.IntegrationEventHandlersBuilder;
import com.flowzati.archone.ordering.application.event.OrderingEventSubscriptions;
import com.flowzati.archone.stock.application.event.PromisingEventTopics;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Ordering subscription for allocation results emitted by Promising. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnIntegrationEventConsumption
public class OrderingAllocationResultEventConfiguration {

  public static final String ORDERING_ALLOCATION_RESULT_HANDLERS =
      "orderingAllocationResultIntegrationEventHandlers";

  @Bean(ORDERING_ALLOCATION_RESULT_HANDLERS)
  IntegrationEventHandlers orderingAllocationResultIntegrationEventHandlers(
      OrderingAllocationResultEventTarget target
  ) {
    return IntegrationEventHandlersBuilder
        .forDestination(PromisingEventTopics.ALLOCATION_EVENTS)
        .onEvent(
            OrderAllocatedIntegrationEvent.class,
            envelope -> target.onOrderAllocated(envelope.event()))
        .onEvent(
            BackorderCreatedIntegrationEvent.class,
            envelope -> target.onBackorderCreated(envelope.event()))
        .build();
  }

  @Bean
  IntegrationEventDispatcher orderingAllocationResultIntegrationEventDispatcher(
      IntegrationEventDispatcherFactory factory,
      @Qualifier(ORDERING_ALLOCATION_RESULT_HANDLERS) IntegrationEventHandlers handlers
  ) {
    return factory.make(
        OrderingEventSubscriptions.ALLOCATION_RESULTS,
        handlers,
        MessageSubscriptionOptions.withConsumerGroupId(
            OrderingEventSubscriptions.ALLOCATION_RESULTS_CONSUMER_GROUP));
  }
}
