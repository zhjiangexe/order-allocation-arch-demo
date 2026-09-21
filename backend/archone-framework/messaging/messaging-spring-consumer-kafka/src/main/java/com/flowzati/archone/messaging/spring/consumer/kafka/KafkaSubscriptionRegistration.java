package com.flowzati.archone.messaging.spring.consumer.kafka;

import java.util.Set;

/** Immutable identity reserved before a Kafka container starts. */
record KafkaSubscriptionRegistration(
        String subscriberId, String consumerGroupId, Set<String> destinations, String containerId) {

    KafkaSubscriptionRegistration {
        destinations = Set.copyOf(destinations);
    }
}
