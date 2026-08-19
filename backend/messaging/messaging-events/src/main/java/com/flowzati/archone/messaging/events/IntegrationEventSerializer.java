package com.flowzati.archone.messaging.events;

/** Serializes a typed Integration Event into its immutable wire payload. */
@FunctionalInterface
public interface IntegrationEventSerializer {

    String serialize(IntegrationEvent event);
}
