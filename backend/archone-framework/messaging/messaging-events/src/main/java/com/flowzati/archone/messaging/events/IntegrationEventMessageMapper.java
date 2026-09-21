package com.flowzati.archone.messaging.events;

import com.flowzati.archone.messaging.api.Message;
import com.flowzati.archone.messaging.api.MessageBuilder;

/** Maps a typed Integration Event publication to the generic immutable message envelope. */
public final class IntegrationEventMessageMapper {

    private final IntegrationEventSerializer serializer;
    private final IntegrationEventNameMapping nameMapping;

    public IntegrationEventMessageMapper(
            IntegrationEventSerializer serializer, IntegrationEventNameMapping nameMapping) {
        if (serializer == null || nameMapping == null) {
            throw new IllegalArgumentException("Integration Event serializer and name mapping are required");
        }
        this.serializer = serializer;
        this.nameMapping = nameMapping;
    }

    public Message toMessage(IntegrationEventPublication publication) {
        if (publication == null) {
            throw new IllegalArgumentException("Integration Event publication is required");
        }

        IntegrationEvent event = publication.event();
        IntegrationEventDescriptor externalType = nameMapping.externalTypeFor(event.getClass());
        if (!externalType.eventType().equals(event.eventType())) {
            throw new IllegalArgumentException("Integration Event class mapping differs from event type: "
                    + event.getClass().getName());
        }
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
                        EventMessageHeaders.EVENT_CONTRACT_VERSION, Integer.toString(externalType.contractVersion()))
                .build();
        EventMessageHeaders.validateForPublication(message);
        return message;
    }
}
