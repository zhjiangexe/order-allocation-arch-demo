package com.flowzati.archone.messaging.events;

import com.flowzati.archone.messaging.api.Message;
import com.flowzati.archone.messaging.api.MessageProducer;

/** Converts a typed event publication into the generic MessageProducer contract. */
public final class DefaultIntegrationEventPublisher implements IntegrationEventPublisher {

  private final MessageProducer messageProducer;
  private final IntegrationEventSerializer serializer;

  public DefaultIntegrationEventPublisher(
      MessageProducer messageProducer,
      IntegrationEventSerializer serializer
  ) {
    this.messageProducer = messageProducer;
    this.serializer = serializer;
  }

  @Override
  public void publish(IntegrationEventPublication publication) {
    IntegrationEvent event = publication.event();
    AggregateReference aggregate = publication.aggregate();
    PublicationTarget target = publication.target();

    messageProducer.send(target.destination(), new Message(
        event.getEventId(),
        event.eventType(),
        aggregate.type(),
        aggregate.id(),
        target.partitionKey(),
        serializer.serialize(event),
        publication.occurredAt()
    ));
  }
}
