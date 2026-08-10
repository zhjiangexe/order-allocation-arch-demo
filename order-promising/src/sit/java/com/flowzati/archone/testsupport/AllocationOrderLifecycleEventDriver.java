package com.flowzati.archone.testsupport;

import static com.flowzati.archone.stock.entrypoint.messaging.AllocationOrderLifecycleEventConfiguration.ALLOCATION_ORDER_LIFECYCLE_HANDLERS;

import com.flowzati.archone.messaging.api.ChannelMapping;
import com.flowzati.archone.messaging.api.ConsumerGroupMapping;
import com.flowzati.archone.messaging.api.MessageSubscriptionOptions;
import com.flowzati.archone.messaging.consumer.common.MessageConsumerImpl;
import com.flowzati.archone.messaging.consumer.common.MessageHandlerDecorator;
import com.flowzati.archone.contracts.ordering.v1.OrderCancelledIntegrationEvent;
import com.flowzati.archone.contracts.ordering.v1.OrderPlacedIntegrationEvent;
import com.flowzati.archone.messaging.events.AggregateReference;
import com.flowzati.archone.messaging.events.IntegrationEvent;
import com.flowzati.archone.messaging.events.IntegrationEventDeserializer;
import com.flowzati.archone.messaging.events.IntegrationEventDispatcherFactory;
import com.flowzati.archone.messaging.events.IntegrationEventDispatcherOptions;
import com.flowzati.archone.messaging.events.IntegrationEventHandlers;
import com.flowzati.archone.messaging.events.IntegrationEventMessageMapper;
import com.flowzati.archone.messaging.events.IntegrationEventNameMapping;
import com.flowzati.archone.messaging.events.IntegrationEventPublication;
import com.flowzati.archone.messaging.events.IntegrationEventSerializer;
import com.flowzati.archone.messaging.events.PublicationTarget;
import com.flowzati.archone.messaging.events.UnhandledIntegrationEventObserver;
import com.flowzati.archone.ordering.application.event.OrderingEventTopics;
import com.flowzati.archone.promising.messaging.OutboxAggregateTypes;
import com.flowzati.archone.stock.application.event.AllocationEventSubscriptions;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Drives the production order-lifecycle typed chain without starting Kafka in PostgreSQL SITs.
 *
 * <p>Business transaction SITs start from the canonical message envelope. Physical Kafka record
 * mapping and serialized-header restoration belong to the dedicated dispatcher equivalence SIT.
 */
@Component
public class AllocationOrderLifecycleEventDriver {

  private final IntegrationEventMessageMapper messageMapper;
  private final DirectMessageConsumerImplementation transport;
  private final String physicalDestination;

  public AllocationOrderLifecycleEventDriver(
      IntegrationEventSerializer serializer,
      IntegrationEventDeserializer deserializer,
      IntegrationEventNameMapping nameMapping,
      ChannelMapping channelMapping,
      ConsumerGroupMapping consumerGroupMapping,
      @Qualifier(ALLOCATION_ORDER_LIFECYCLE_HANDLERS) IntegrationEventHandlers handlers,
      UnhandledIntegrationEventObserver observer,
      List<MessageHandlerDecorator> decorators
  ) {
    this.messageMapper = new IntegrationEventMessageMapper(serializer);
    this.transport = new DirectMessageConsumerImplementation();
    this.physicalDestination = channelMapping.transform(OrderingEventTopics.ORDER_EVENTS);
    MessageConsumerImpl consumer = new MessageConsumerImpl(
        transport,
        channelMapping,
        consumerGroupMapping,
        decorators);
    new IntegrationEventDispatcherFactory(consumer, deserializer, nameMapping).make(
        AllocationEventSubscriptions.ORDER_LIFECYCLE,
        handlers,
        IntegrationEventDispatcherOptions.builder()
            .subscriptionOptions(MessageSubscriptionOptions.withConsumerGroupId(
                AllocationEventSubscriptions.ORDER_LIFECYCLE_CONSUMER_GROUP))
            .ignoreUnhandledEventsWith(observer)
            .build());
  }

  public void consume(OrderPlacedIntegrationEvent event) {
    consume(event, event.getOrderId().toString(), event.getReceivedAt());
  }

  public void consume(OrderCancelledIntegrationEvent event) {
    consume(event, event.getOrderId().toString(), event.getCancelledAt());
  }

  private void consume(
      IntegrationEvent event,
      String orderId,
      Instant occurredAt
  ) {
    IntegrationEventPublication publication = new IntegrationEventPublication(
        event,
        new AggregateReference(OutboxAggregateTypes.ORDER, orderId),
        new PublicationTarget(OrderingEventTopics.ORDER_EVENTS, orderId),
        occurredAt);
    transport.emit(physicalDestination, messageMapper.toMessage(publication));
  }
}
