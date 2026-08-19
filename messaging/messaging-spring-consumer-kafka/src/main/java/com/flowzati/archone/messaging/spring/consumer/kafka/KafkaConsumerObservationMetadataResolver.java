package com.flowzati.archone.messaging.spring.consumer.kafka;

import com.flowzati.archone.messaging.consumer.common.ResolvedMessageSubscription;
import java.util.Collection;
import org.apache.kafka.clients.consumer.ConsumerRecord;

/** Resolves bounded observation tags without treating a physical Kafka topic as domain identity. */
@FunctionalInterface
public interface KafkaConsumerObservationMetadataResolver {

    KafkaConsumerObservationMetadata resolve(ConsumerRecord<?, ?> record);

    static KafkaConsumerObservationMetadataResolver forSubscription(ResolvedMessageSubscription subscription) {
        return forSubscriptions(java.util.List.of(subscription));
    }

    static KafkaConsumerObservationMetadataResolver forSubscriptions(
            Collection<ResolvedMessageSubscription> subscriptions) {
        ResolvedKafkaSubscriptionIndex index =
                ResolvedKafkaSubscriptionIndex.create(subscriptions, "Kafka observation");
        return record -> {
            if (record == null) {
                throw new IllegalArgumentException("Kafka consumer record is required");
            }
            ResolvedMessageSubscription subscription = index.resolve(record.topic());
            return new KafkaConsumerObservationMetadata(
                    subscription.subscriberId(), subscription.logicalChannelFor(record.topic()));
        };
    }
}
