package com.flowzati.archone.messaging.api;

import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable explicit subscriber-to-group mapping with identity fallback. */
public final class MapBasedConsumerGroupMapping implements ConsumerGroupMapping {

    private final Map<String, String> mappings;

    public MapBasedConsumerGroupMapping(Map<String, String> mappings) {
        if (mappings == null) {
            throw new IllegalArgumentException("Consumer group mappings are required");
        }
        LinkedHashMap<String, String> validated = new LinkedHashMap<>();
        mappings.forEach((subscriberId, consumerGroupId) -> {
            if (isBlank(subscriberId) || isBlank(consumerGroupId)) {
                throw new IllegalArgumentException("Subscriber and consumer group IDs are required");
            }
            validated.put(subscriberId, consumerGroupId);
        });
        this.mappings = Map.copyOf(validated);
    }

    @Override
    public String transform(String subscriberId) {
        if (isBlank(subscriberId)) {
            throw new IllegalArgumentException("Subscriber ID is required");
        }
        return mappings.getOrDefault(subscriberId, subscriberId);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
