package com.flowzati.archone.messaging.events;

import com.flowzati.archone.messaging.api.Message;
import com.flowzati.archone.messaging.api.MessageBuilder;

/** Maps a typed Integration Event publication to the generic immutable message envelope. */
public final class IntegrationEventMessageMapper {

    private final IntegrationEventSerializer serializer;

    public IntegrationEventMessageMapper(IntegrationEventSerializer serializer) {
        if (serializer == null) {
            throw new IllegalArgumentException("Integration Event serializer is required");
        }
        this.serializer = serializer;
    }

    public Message toMessage(IntegrationEventPublication publication) {
        if (publication == null) {
            throw new IllegalArgumentException("Integration Event publication is required");
        }

        IntegrationEvent event = publication.event();
        AggregateReference aggregate = publication.aggregate();
        PublicationTarget target = publication.target();
        Message message = MessageBuilder.withPayload(serializer.serialize(event))
                .withId(event.getEventId())
                .withType(event.eventType())
                .withPartitionId(target.partitionKey())
                .withMessageDate(publication.occurredAt())
                .withHeader(EventMessageHeaders.EVENT_TYPE, event.eventType())
                .withHeader(EventMessageHeaders.EVENT_AGGREGATE_TYPE, aggregate.type())
                .withHeader(EventMessageHeaders.EVENT_AGGREGATE_ID, aggregate.id())
                .withHeader(
                        EventMessageHeaders.EVENT_CONTRACT_VERSION,
                        Integer.toString(EventMessageHeaders.INITIAL_CONTRACT_VERSION))
                .build();
        EventMessageHeaders.validateForPublication(message);
        return message;
    }
}
