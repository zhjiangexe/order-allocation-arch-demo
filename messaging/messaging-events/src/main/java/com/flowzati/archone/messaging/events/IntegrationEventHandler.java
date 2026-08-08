package com.flowzati.archone.messaging.events;

import com.flowzati.archone.messaging.api.MessageMetadata;

/**
 * Broker-neutral typed Integration Event handler contract.
 *
 * <p>{@code destination()} is a logical messaging destination. Kafka topic binding belongs to the
 * application listener/runtime and is not part of the handler type.
 */
public interface IntegrationEventHandler<E extends IntegrationEvent> {

  String destination();

  String eventType();

  Class<E> eventClass();

  void handleTyped(E event, MessageMetadata metadata);

  default void handle(IntegrationEvent event, MessageMetadata metadata) {
    handleTyped(eventClass().cast(event), metadata);
  }
}
