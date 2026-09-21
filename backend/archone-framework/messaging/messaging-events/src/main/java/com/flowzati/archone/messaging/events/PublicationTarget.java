package com.flowzati.archone.messaging.events;

/** Logical destination and ordering key selected by the publishing bounded context. */
public record PublicationTarget(String destination, String partitionKey) {
    public PublicationTarget {
        if (destination == null || destination.isBlank() || partitionKey == null || partitionKey.isBlank()) {
            throw new IllegalArgumentException("Publication destination and partition key are required");
        }
    }
}
