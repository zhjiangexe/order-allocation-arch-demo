package com.flowzati.archone.messaging.kafka;

import com.flowzati.archone.messaging.api.Message;
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
 */
public final class KafkaIntegrationEventDispatcher {

  private final KafkaMessageMapper kafkaMessageMapper;
  private final IntegrationEventDispatcher eventDispatcher;

  public KafkaIntegrationEventDispatcher(
      IntegrationEventDeserializer deserializer,
      List<IntegrationEventHandler<?>> handlers
  ) {
    this(new KafkaMessageMapper(), new IntegrationEventDispatcher(deserializer, handlers));
  }

  public KafkaIntegrationEventDispatcher(
      IntegrationEventDeserializer deserializer,
      List<IntegrationEventHandler<?>> handlers,
      MessageHeadersDecoder headersDecoder
  ) {
    this(
        new KafkaMessageMapper(headersDecoder),
        new IntegrationEventDispatcher(deserializer, handlers));
  }

  KafkaIntegrationEventDispatcher(
      KafkaMessageMapper kafkaMessageMapper,
      IntegrationEventDispatcher eventDispatcher
  ) {
    this.kafkaMessageMapper = kafkaMessageMapper;
    this.eventDispatcher = eventDispatcher;
  }

  public void dispatch(
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
    eventDispatcher.dispatch(message, expectedDestination, subscriberId);
  }
}
