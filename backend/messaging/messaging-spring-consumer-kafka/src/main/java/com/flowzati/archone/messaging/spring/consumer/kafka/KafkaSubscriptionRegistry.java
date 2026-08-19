package com.flowzati.archone.messaging.spring.consumer.kafka;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Process-local collision guard for programmatic Kafka subscriptions. */
final class KafkaSubscriptionRegistry {

    private final Map<String, KafkaSubscriptionRegistration> registrations = new LinkedHashMap<>();

    synchronized void reserve(KafkaSubscriptionRegistration candidate) {
        KafkaSubscriptionRegistration duplicate = registrations.get(candidate.subscriberId());
        if (duplicate != null) {
            throw new IllegalStateException("Duplicate Kafka subscriber ID: " + candidate.subscriberId());
        }
        registrations.values().forEach(existing -> validateNoCollision(existing, candidate));
        registrations.put(candidate.subscriberId(), candidate);
    }

    synchronized void release(KafkaSubscriptionRegistration registration) {
        registrations.remove(registration.subscriberId(), registration);
    }

    private void validateNoCollision(KafkaSubscriptionRegistration existing, KafkaSubscriptionRegistration candidate) {
        if (existing.containerId().equals(candidate.containerId())) {
            throw new IllegalStateException("Duplicate Kafka container ID: " + candidate.containerId());
        }
        if (!existing.consumerGroupId().equals(candidate.consumerGroupId())) {
            return;
        }
        Set<String> overlap = new java.util.LinkedHashSet<>(existing.destinations());
        overlap.retainAll(candidate.destinations());
        if (!overlap.isEmpty()) {
            throw new IllegalStateException("Kafka subscribers must not share group and destinations: "
                    + candidate.consumerGroupId() + "/" + overlap);
        }
    }
}
