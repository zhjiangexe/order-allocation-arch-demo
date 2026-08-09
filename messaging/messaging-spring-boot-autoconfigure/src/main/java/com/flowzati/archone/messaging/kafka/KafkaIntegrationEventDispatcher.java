package com.flowzati.archone.messaging.kafka;

import com.flowzati.archone.messaging.api.Message;
import com.flowzati.archone.messaging.api.MessageContext;
import com.flowzati.archone.messaging.api.MessageHandler;
import com.flowzati.archone.messaging.api.MessageHeadersDecoder;
import com.flowzati.archone.messaging.consumer.common.MessageHandlerDecorator;
import com.flowzati.archone.messaging.consumer.common.MessageHandlerDecoratorChain;
import com.flowzati.archone.messaging.consumer.common.MessageHandlerInvocation;
import com.flowzati.archone.messaging.consumer.common.ProcessingOutcome;
import com.flowzati.archone.messaging.events.EventMessageHeaders;
import com.flowzati.archone.messaging.events.IntegrationEventDeserializer;
import com.flowzati.archone.messaging.events.IntegrationEventHandler;
import com.flowzati.archone.messaging.events.LegacyIntegrationEventDispatcherAdapter;
import java.util.List;
import java.util.Objects;
import org.apache.kafka.clients.consumer.ConsumerRecord;

/**
 * Temporary source-compatible bridge for existing application-owned {@code @KafkaListener}s.
 *
 * <p>Kafka record mapping now belongs to {@code messaging-consumer-kafka}; typed dispatch belongs
 * to {@code messaging-events}. Gate I removes this bridge after applications subscribe through
 * the generic programmatic consumer runtime.
 *
 * <p>The explicit-decorator constructor prepares Gate E's single inbound chain. Compatibility
 * constructors deliberately use an empty decorator list until an application atomically removes
 * its legacy use-case Inbox claims.
 */
public final class KafkaIntegrationEventDispatcher {

  private final KafkaMessageMapper kafkaMessageMapper;
  private final MessageHandler terminalHandler;
  private final List<MessageHandlerDecorator> decorators;

  public KafkaIntegrationEventDispatcher(
      IntegrationEventDeserializer deserializer,
      List<IntegrationEventHandler<?>> handlers
  ) {
    this(
        new KafkaMessageMapper(),
        legacyMessageHandler(new LegacyIntegrationEventDispatcherAdapter(deserializer, handlers)),
        List.of());
  }

  public KafkaIntegrationEventDispatcher(
      IntegrationEventDeserializer deserializer,
      List<IntegrationEventHandler<?>> handlers,
      MessageHeadersDecoder headersDecoder
  ) {
    this(
        new KafkaMessageMapper(headersDecoder),
        legacyMessageHandler(new LegacyIntegrationEventDispatcherAdapter(deserializer, handlers)),
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
        legacyMessageHandler(new LegacyIntegrationEventDispatcherAdapter(deserializer, handlers)),
        decorators);
  }

  /**
   * FS4 bridge constructor: maps the legacy raw Kafka entrypoint into any broker-neutral handler.
   *
   * <p>It lets equivalence tests exercise the new typed dispatcher without starting another Kafka
   * consumer. Production continues to use the legacy constructors until Gate I performs an atomic
   * subscriber cutover.
   */
  public KafkaIntegrationEventDispatcher(
      MessageHeadersDecoder headersDecoder,
      MessageHandler terminalHandler,
      List<MessageHandlerDecorator> decorators
  ) {
    this(new KafkaMessageMapper(headersDecoder), terminalHandler, decorators);
  }

  KafkaIntegrationEventDispatcher(
      KafkaMessageMapper kafkaMessageMapper,
      MessageHandler terminalHandler,
      List<MessageHandlerDecorator> decorators
  ) {
    this.kafkaMessageMapper = Objects.requireNonNull(
        kafkaMessageMapper, "Kafka message mapper is required");
    this.terminalHandler = Objects.requireNonNull(
        terminalHandler, "Broker-neutral message handler is required");
    if (decorators == null || decorators.stream().anyMatch(Objects::isNull)) {
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
    MessageHandlerDecoratorChain chain = MessageHandlerDecoratorChain.create(
        decorators,
        this::dispatchTerminal);
    return chain.invokeNext(new MessageHandlerInvocation(
        message,
        new MessageContext(subscriberId, expectedDestination, 1)));
  }

  private ProcessingOutcome dispatchTerminal(MessageHandlerInvocation invocation) {
    terminalHandler.handle(invocation.message(), invocation.context());
    return ProcessingOutcome.PROCESSED;
  }

  private static MessageHandler legacyMessageHandler(
      LegacyIntegrationEventDispatcherAdapter eventDispatcher
  ) {
    return (message, context) -> {
      String eventType = EventMessageHeaders.eventType(message);
      if (!eventDispatcher.supports(context.logicalChannel(), eventType)) {
        throw new IllegalArgumentException("Unsupported Kafka integration event: "
            + context.logicalChannel() + "/" + eventType);
      }
      eventDispatcher.dispatch(
          message,
          context.logicalChannel(),
          context.subscriberId());
    };
  }
}
