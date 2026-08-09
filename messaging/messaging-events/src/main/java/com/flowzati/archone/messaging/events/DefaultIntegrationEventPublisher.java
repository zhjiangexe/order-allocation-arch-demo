package com.flowzati.archone.messaging.events;

import com.flowzati.archone.messaging.api.MessageProducer;

/** Converts a typed event publication into the generic MessageProducer contract. */
public final class DefaultIntegrationEventPublisher implements IntegrationEventPublisher {

  private final MessageProducer messageProducer;
  private final IntegrationEventMessageMapper messageMapper;

  public DefaultIntegrationEventPublisher(
      MessageProducer messageProducer,
      IntegrationEventSerializer serializer
  ) {
    this(messageProducer, new IntegrationEventMessageMapper(serializer));
  }

  public DefaultIntegrationEventPublisher(
      MessageProducer messageProducer,
      IntegrationEventMessageMapper messageMapper
  ) {
    if (messageProducer == null || messageMapper == null) {
      throw new IllegalArgumentException("Message producer and event mapper are required");
    }
    this.messageProducer = messageProducer;
    this.messageMapper = messageMapper;
  }

  @Override
  public void publish(IntegrationEventPublication publication) {
    messageProducer.send(publication.target().destination(), messageMapper.toMessage(publication));
  }
}
