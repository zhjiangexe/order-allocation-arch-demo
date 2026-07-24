package com.flowzati.archone.common.messaging.kafka;

import com.flowzati.archone.common.inbox.MessageMetadata;
import com.flowzati.archone.common.integration.IntegrationEvent;

/** Maps one typed Integration Event to this bounded context's application command. */
public interface IntegrationEventHandler<E extends IntegrationEvent> {

  String topic();

  Class<E> eventClass();

  void handleTyped(E event, MessageMetadata metadata);

  default String eventType() {
    return eventClass().getSimpleName();
  }

  default void handle(IntegrationEvent event, MessageMetadata metadata) {
    handleTyped(eventClass().cast(event), metadata);
  }
}
