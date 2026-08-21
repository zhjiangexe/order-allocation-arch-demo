package com.flowzati.archone.messaging.events;

import java.util.Optional;

/** Maps Java event classes to explicit stable external type/version identities and back. */
public interface IntegrationEventNameMapping {

    IntegrationEventDescriptor externalTypeFor(Class<? extends IntegrationEvent> eventClass);

    Optional<Class<? extends IntegrationEvent>> eventClassFor(IntegrationEventDescriptor externalType);

    default Optional<Class<? extends IntegrationEvent>> eventClassFor(String eventType, int contractVersion) {
        return eventClassFor(new IntegrationEventDescriptor(eventType, contractVersion));
    }
}
