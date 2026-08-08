package com.flowzati.archone.messaging.events;

/** Restores a typed Integration Event from its wire payload. */
@FunctionalInterface
public interface IntegrationEventDeserializer {

  <E extends IntegrationEvent> E deserialize(String payload, Class<E> eventClass);
}
