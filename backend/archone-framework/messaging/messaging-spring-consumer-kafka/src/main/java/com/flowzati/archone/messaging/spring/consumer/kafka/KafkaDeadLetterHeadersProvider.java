package com.flowzati.archone.messaging.spring.consumer.kafka;

import com.flowzati.archone.messaging.consumer.common.ResolvedMessageSubscription;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Objects;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;

/** Supplies replay-safe subscriber metadata in addition to Spring Kafka's DLT headers. */
@FunctionalInterface
public interface KafkaDeadLetterHeadersProvider {

    Headers headersFor(ConsumerRecord<?, ?> record, Exception failure);

    /** Derives exact logical and physical identities from one resolved programmatic subscription. */
    static KafkaDeadLetterHeadersProvider forSubscription(ResolvedMessageSubscription subscription) {
        return forSubscriptions(java.util.List.of(subscription));
    }

    /**
     * Supports a legacy global error handler only when each physical topic identifies one subscriber.
     */
    static KafkaDeadLetterHeadersProvider forSubscriptions(Collection<ResolvedMessageSubscription> subscriptions) {
        if (subscriptions == null
                || subscriptions.isEmpty()
                || subscriptions.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Resolved message subscriptions are required");
        }
        ResolvedKafkaSubscriptionIndex index = ResolvedKafkaSubscriptionIndex.create(subscriptions, "DLT");
        return (record, failure) -> {
            ResolvedMessageSubscription subscription = index.resolve(record.topic());
            return channelHeaders(
                    subscription.logicalChannelFor(record.topic()),
                    record.topic(),
                    subscription.subscriberId(),
                    subscription.consumerGroupId());
        };
    }

    private static Headers channelHeaders(
            String logicalChannel, String physicalDestination, String subscriberId, String consumerGroupId) {
        RecordHeaders headers = new RecordHeaders();
        add(headers, KafkaDeadLetterHeaders.ORIGINAL_LOGICAL_CHANNEL, logicalChannel);
        add(headers, KafkaDeadLetterHeaders.ORIGINAL_PHYSICAL_DESTINATION, physicalDestination);
        if (subscriberId != null) {
            add(headers, KafkaDeadLetterHeaders.SUBSCRIBER_ID, subscriberId);
        }
        if (consumerGroupId != null) {
            add(headers, KafkaDeadLetterHeaders.CONSUMER_GROUP_ID, consumerGroupId);
        }
        return headers;
    }

    private static void add(Headers headers, String name, String value) {
        headers.add(name, value.getBytes(StandardCharsets.UTF_8));
    }
}
