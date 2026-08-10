package com.flowzati.archone.testsupport;

import static com.flowzati.archone.ordering.entrypoint.messaging.OrderingAllocationResultEventConfiguration.ORDERING_ALLOCATION_RESULT_HANDLERS;

import com.flowzati.archone.messaging.api.ChannelMapping;
import com.flowzati.archone.messaging.api.ConsumerGroupMapping;
import com.flowzati.archone.messaging.api.MessageSubscriptionOptions;
import com.flowzati.archone.messaging.consumer.common.MessageConsumerImpl;
import com.flowzati.archone.messaging.consumer.common.MessageHandlerDecorator;
import com.flowzati.archone.messaging.events.IntegrationEventDeserializer;
import com.flowzati.archone.messaging.events.IntegrationEventDispatcherFactory;
import com.flowzati.archone.messaging.events.IntegrationEventHandlers;
import com.flowzati.archone.messaging.events.IntegrationEventNameMapping;
import com.flowzati.archone.messaging.kafka.KafkaMessageMapper;
import com.flowzati.archone.ordering.application.event.OrderingEventSubscriptions;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Builds an in-memory transport around the same typed dispatcher and decorators used at runtime. */
@Component
public class AllocationOutcomeDrainFactory {

  private final JdbcTemplate jdbcTemplate;
  private final KafkaMessageMapper messageMapper;
  private final IntegrationEventDeserializer deserializer;
  private final IntegrationEventNameMapping nameMapping;
  private final ChannelMapping channelMapping;
  private final ConsumerGroupMapping consumerGroupMapping;
  private final IntegrationEventHandlers handlers;
  private final List<MessageHandlerDecorator> decorators;

  public AllocationOutcomeDrainFactory(
      JdbcTemplate jdbcTemplate,
      KafkaMessageMapper messageMapper,
      IntegrationEventDeserializer deserializer,
      IntegrationEventNameMapping nameMapping,
      ChannelMapping channelMapping,
      ConsumerGroupMapping consumerGroupMapping,
      @Qualifier(ORDERING_ALLOCATION_RESULT_HANDLERS) IntegrationEventHandlers handlers,
      List<MessageHandlerDecorator> decorators
  ) {
    this.jdbcTemplate = jdbcTemplate;
    this.messageMapper = messageMapper;
    this.deserializer = deserializer;
    this.nameMapping = nameMapping;
    this.channelMapping = channelMapping;
    this.consumerGroupMapping = consumerGroupMapping;
    this.handlers = handlers;
    this.decorators = List.copyOf(decorators);
  }

  public AllocationOutcomeDrain create() {
    DirectMessageConsumerImplementation transport = new DirectMessageConsumerImplementation();
    MessageConsumerImpl consumer = new MessageConsumerImpl(
        transport,
        channelMapping,
        consumerGroupMapping,
        decorators);
    new IntegrationEventDispatcherFactory(consumer, deserializer, nameMapping).make(
        OrderingEventSubscriptions.ALLOCATION_RESULTS,
        handlers,
        MessageSubscriptionOptions.withConsumerGroupId(
            OrderingEventSubscriptions.ALLOCATION_RESULTS_CONSUMER_GROUP));
    return new AllocationOutcomeDrain(jdbcTemplate, messageMapper, transport::emit);
  }
}
