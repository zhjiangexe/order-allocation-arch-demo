package com.flowzati.archone.messaging.testsupport;

import com.flowzati.archone.messaging.api.Message;
import com.flowzati.archone.messaging.api.MessageBuilder;
import com.flowzati.archone.messaging.events.EventMessageHeaders;
import com.flowzati.archone.messaging.events.IntegrationEvent;
import com.flowzati.archone.messaging.events.IntegrationEventEnvelope;

/** Creates a transport-free typed envelope for direct Integration Event handler unit tests. */
public final class IntegrationEventHandlerTestFixture {

  private IntegrationEventHandlerTestFixture() {
  }

  public static <E extends IntegrationEvent> IntegrationEventEnvelope<E> envelope(
      E event,
      String aggregateType,
      String aggregateId
  ) {
    if (event == null) {
      throw new IllegalArgumentException("Integration Event is required");
    }
    Message message = MessageBuilder.withPayload("{\"fixture\":true}")
        .withId(event.getEventId())
        .withType(event.eventType())
        .withPartitionId(aggregateId)
        .withMessageDate(MessageFixtures.MESSAGE_DATE)
        .withHeader(EventMessageHeaders.EVENT_TYPE, event.eventType())
        .withHeader(EventMessageHeaders.EVENT_AGGREGATE_TYPE, aggregateType)
        .withHeader(EventMessageHeaders.EVENT_AGGREGATE_ID, aggregateId)
        .withHeader(
            EventMessageHeaders.EVENT_CONTRACT_VERSION,
            Integer.toString(EventMessageHeaders.INITIAL_CONTRACT_VERSION))
        .build();
    EventMessageHeaders.validateForPublication(message);
    return new IntegrationEventEnvelope<>(
        message,
        aggregateType,
        aggregateId,
        event.getEventId(),
        event);
  }
}
