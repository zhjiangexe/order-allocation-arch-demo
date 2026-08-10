package com.flowzati.archone.bootstrap.messaging;

import com.flowzati.archone.contracts.inventory.v1.StockAvailabilityIncreasedIntegrationEvent;
import com.flowzati.archone.contracts.ordering.v1.OrderCancelledIntegrationEvent;
import com.flowzati.archone.contracts.ordering.v1.OrderPlacedIntegrationEvent;
import com.flowzati.archone.contracts.promising.v1.BackorderCreatedIntegrationEvent;
import com.flowzati.archone.contracts.promising.v1.OrderAllocatedIntegrationEvent;
import com.flowzati.archone.messaging.events.EventMessageHeaders;
import com.flowzati.archone.messaging.events.IntegrationEventNameMapping;
import com.flowzati.archone.messaging.events.MapBasedIntegrationEventNameMapping;
import com.flowzati.archone.messaging.events.UnhandledIntegrationEventObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Stable wire type allow-list and shared diagnostics for consumed Integration Events. */
@Configuration(proxyBeanMethods = false)
public class OrderPromisingEventContractConfiguration {

  private static final Logger LOGGER = LoggerFactory.getLogger(
      OrderPromisingEventContractConfiguration.class);

  @Bean
  IntegrationEventNameMapping orderPromisingIntegrationEventNameMapping() {
    return MapBasedIntegrationEventNameMapping.builder()
        .map(
            OrderAllocatedIntegrationEvent.class,
            OrderAllocatedIntegrationEvent.EVENT_TYPE,
            EventMessageHeaders.INITIAL_CONTRACT_VERSION)
        .map(
            BackorderCreatedIntegrationEvent.class,
            BackorderCreatedIntegrationEvent.EVENT_TYPE,
            EventMessageHeaders.INITIAL_CONTRACT_VERSION)
        .map(
            OrderPlacedIntegrationEvent.class,
            OrderPlacedIntegrationEvent.EVENT_TYPE,
            EventMessageHeaders.INITIAL_CONTRACT_VERSION)
        .map(
            OrderCancelledIntegrationEvent.class,
            OrderCancelledIntegrationEvent.EVENT_TYPE,
            EventMessageHeaders.INITIAL_CONTRACT_VERSION)
        .map(
            StockAvailabilityIncreasedIntegrationEvent.class,
            StockAvailabilityIncreasedIntegrationEvent.EVENT_TYPE,
            EventMessageHeaders.INITIAL_CONTRACT_VERSION)
        .build();
  }

  /** Reason-rich diagnostic; the generic consumer observation owns the bounded outcome metric. */
  @Bean
  UnhandledIntegrationEventObserver orderPromisingUnhandledIntegrationEventObserver() {
    return event -> LOGGER.debug(
        "Ignored unhandled Integration Event: destination={}, eventType={}, "
            + "contractVersion={}, reason={}, messageId={}",
        event.destination(),
        event.eventType(),
        event.contractVersion(),
        event.reason(),
        event.message().id());
  }
}
