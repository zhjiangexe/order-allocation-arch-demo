package com.flowzati.archone.messaging.kafka;

import com.flowzati.archone.messaging.api.Message;
import com.flowzati.archone.messaging.api.MessageContext;
import com.flowzati.archone.messaging.consumer.common.MessageHandlerDecorator;
import com.flowzati.archone.messaging.consumer.common.MessageHandlerDecoratorChain;
import com.flowzati.archone.messaging.consumer.common.MessageHandlerInvocation;
import com.flowzati.archone.messaging.consumer.common.ProcessingOutcome;
import com.flowzati.archone.messaging.events.EventMessageHeaders;
import com.flowzati.archone.messaging.events.IntegrationEventDeserializer;
import com.flowzati.archone.messaging.events.IntegrationEventDispatcher;
import com.flowzati.archone.messaging.events.IntegrationEventHandler;
import com.flowzati.archone.messaging.api.MessageHeadersDecoder;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;

/**
 * Temporary source-compatible bridge for existing application-owned {@code @KafkaListener}s.
 *
 * <p>Kafka record mapping now belongs to {@code messaging-consumer-kafka}; typed dispatch belongs
 * to {@code messaging-events}. Gate F removes this bridge when applications subscribe through the
 * generic programmatic consumer runtime.
 *
 * <p>The explicit-decorator constructor prepares Gate E's single inbound chain. Compatibility
 * constructors deliberately use an empty decorator list until an application atomically removes
 * its legacy use-case Inbox claims.
 */
public final class KafkaIntegrationEventDispatcher {

  private final KafkaMessageMapper kafkaMessageMapper;
  private final IntegrationEventDispatcher eventDispatcher;
  private final List<MessageHandlerDecorator> decorators;

  public KafkaIntegrationEventDispatcher(
      IntegrationEventDeserializer deserializer,
      List<IntegrationEventHandler<?>> handlers
  ) {
    this(
        new KafkaMessageMapper(),
        new IntegrationEventDispatcher(deserializer, handlers),
        List.of());
  }

  public KafkaIntegrationEventDispatcher(
      IntegrationEventDeserializer deserializer,
      List<IntegrationEventHandler<?>> handlers,
      MessageHeadersDecoder headersDecoder
  ) {
    this(
        new KafkaMessageMapper(headersDecoder),
        new IntegrationEventDispatcher(deserializer, handlers),
        List.of());
  }

  public KafkaIntegrationEventDispatcher(
      IntegrationEventDeserializer deserializer,
      List<IntegrationEventHandler<?>> handlers,
      MessageHeadersDecoder headersDecoder,
      List<MessageHandlerDecorator> decorators
  ) {
    this(
        new KafkaMessageMapper(headersDecoder),
        new IntegrationEventDispatcher(deserializer, handlers),
        decorators);
  }

  KafkaIntegrationEventDispatcher(
      KafkaMessageMapper kafkaMessageMapper,
      IntegrationEventDispatcher eventDispatcher,
      List<MessageHandlerDecorator> decorators
  ) {
    this.kafkaMessageMapper = kafkaMessageMapper;
    this.eventDispatcher = eventDispatcher;
    if (decorators == null || decorators.stream().anyMatch(java.util.Objects::isNull)) {
      throw new IllegalArgumentException("Message handler decorators are required");
    }
    this.decorators = List.copyOf(decorators);
  }

  public ProcessingOutcome dispatch(
      ConsumerRecord<String, String> record,
      String expectedDestination,
      String subscriberId
  ) {
    Message message = kafkaMessageMapper.map(record);
    String eventType = EventMessageHeaders.eventType(message);
    if (!eventDispatcher.supports(expectedDestination, eventType)) {
      throw new IllegalArgumentException("Unsupported Kafka integration event: "
          + expectedDestination + "/" + eventType);
    }
    MessageHandlerDecoratorChain chain = MessageHandlerDecoratorChain.create(
        decorators,
        invocation -> dispatchTyped(invocation));
    return chain.invokeNext(new MessageHandlerInvocation(
        message,
        new MessageContext(subscriberId, expectedDestination, 1)));
  }

  private ProcessingOutcome dispatchTyped(MessageHandlerInvocation invocation) {
    eventDispatcher.dispatch(
        invocation.message(),
        invocation.context().logicalChannel(),
        invocation.context().subscriberId());
    return ProcessingOutcome.PROCESSED;
  }
}
