package com.flowzati.archone.testsupport;

import static com.flowzati.archone.stock.entrypoint.messaging.AllocationInventoryAvailabilityEventConfiguration.ALLOCATION_INVENTORY_AVAILABILITY_HANDLERS;

import com.flowzati.archone.messaging.api.ChannelMapping;
import com.flowzati.archone.messaging.api.ConsumerGroupMapping;
import com.flowzati.archone.messaging.api.MessageSubscriptionOptions;
import com.flowzati.archone.messaging.consumer.common.MessageConsumerImpl;
import com.flowzati.archone.messaging.consumer.common.MessageHandlerDecorator;
import com.flowzati.archone.messaging.events.IntegrationEventDeserializer;
import com.flowzati.archone.messaging.events.IntegrationEventDispatcherFactory;
import com.flowzati.archone.messaging.events.IntegrationEventDispatcherOptions;
import com.flowzati.archone.messaging.events.IntegrationEventHandlers;
import com.flowzati.archone.messaging.events.IntegrationEventNameMapping;
import com.flowzati.archone.messaging.events.UnhandledIntegrationEventObserver;
import com.flowzati.archone.messaging.kafka.KafkaMessageMapper;
import com.flowzati.archone.stock.application.event.AllocationEventSubscriptions;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Builds an in-memory transport around the production inventory-availability typed chain. */
@Component
public class InventoryEventDrainFactory {

  private final JdbcTemplate jdbcTemplate;
  private final KafkaMessageMapper messageMapper;
  private final IntegrationEventDeserializer deserializer;
  private final IntegrationEventNameMapping nameMapping;
  private final ChannelMapping channelMapping;
  private final ConsumerGroupMapping consumerGroupMapping;
  private final IntegrationEventHandlers handlers;
  private final UnhandledIntegrationEventObserver observer;
  private final List<MessageHandlerDecorator> decorators;

  public InventoryEventDrainFactory(
      JdbcTemplate jdbcTemplate,
      KafkaMessageMapper messageMapper,
      IntegrationEventDeserializer deserializer,
      IntegrationEventNameMapping nameMapping,
      ChannelMapping channelMapping,
      ConsumerGroupMapping consumerGroupMapping,
      @Qualifier(ALLOCATION_INVENTORY_AVAILABILITY_HANDLERS) IntegrationEventHandlers handlers,
      UnhandledIntegrationEventObserver observer,
      List<MessageHandlerDecorator> decorators
  ) {
    this.jdbcTemplate = jdbcTemplate;
    this.messageMapper = messageMapper;
    this.deserializer = deserializer;
    this.nameMapping = nameMapping;
    this.channelMapping = channelMapping;
    this.consumerGroupMapping = consumerGroupMapping;
    this.handlers = handlers;
    this.observer = observer;
    this.decorators = List.copyOf(decorators);
  }

  public InventoryEventDrain create() {
    DirectMessageConsumerImplementation transport = new DirectMessageConsumerImplementation();
    MessageConsumerImpl consumer = new MessageConsumerImpl(
        transport,
        channelMapping,
        consumerGroupMapping,
        decorators);
    new IntegrationEventDispatcherFactory(consumer, deserializer, nameMapping).make(
        AllocationEventSubscriptions.INVENTORY_AVAILABILITY,
        handlers,
        IntegrationEventDispatcherOptions.builder()
            .subscriptionOptions(MessageSubscriptionOptions.withConsumerGroupId(
                AllocationEventSubscriptions.INVENTORY_AVAILABILITY_CONSUMER_GROUP))
            .ignoreUnhandledEventsWith(observer)
            .build());
    return new InventoryEventDrain(jdbcTemplate, messageMapper, transport::emit);
  }
}
