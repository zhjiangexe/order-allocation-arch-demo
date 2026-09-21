package com.flowzati.archone.messaging.events;

import java.time.Instant;
import java.util.Collection;

/**
 * Transactional publication port used by application code.
 *
 * <p>The call is synchronous: returning successfully means the configured producer accepted the
 * message. With the Outbox producer, that means the Outbox row participates in the caller's current
 * transaction; it does not mean Kafka already received the message.
 */
@FunctionalInterface
public interface IntegrationEventPublisher {

    void publish(IntegrationEventPublication publication);

    default void publish(
            IntegrationEvent event, AggregateReference aggregate, PublicationTarget target, Instant occurredAt) {
        publish(new IntegrationEventPublication(event, aggregate, target, occurredAt));
    }

    default void publishAll(Collection<IntegrationEventPublication> publications) {
        if (publications == null) {
            throw new IllegalArgumentException("Integration Event publications are required");
        }
        publications.forEach(this::publish);
    }
}
