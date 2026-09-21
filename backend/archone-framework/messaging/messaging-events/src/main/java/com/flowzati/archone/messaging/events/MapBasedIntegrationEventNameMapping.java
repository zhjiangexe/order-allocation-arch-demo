package com.flowzati.archone.messaging.events;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Explicit immutable Integration Event class/type mapping. */
public final class MapBasedIntegrationEventNameMapping implements IntegrationEventNameMapping {

    private final Map<Class<? extends IntegrationEvent>, IntegrationEventDescriptor> externalTypes;
    private final Map<IntegrationEventDescriptor, Class<? extends IntegrationEvent>> eventClasses;

    private MapBasedIntegrationEventNameMapping(
            Map<Class<? extends IntegrationEvent>, IntegrationEventDescriptor> externalTypes,
            Map<IntegrationEventDescriptor, Class<? extends IntegrationEvent>> eventClasses) {
        this.externalTypes = Map.copyOf(externalTypes);
        this.eventClasses = Map.copyOf(eventClasses);
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public IntegrationEventDescriptor externalTypeFor(Class<? extends IntegrationEvent> eventClass) {
        Objects.requireNonNull(eventClass, "Integration Event class is required");
        IntegrationEventDescriptor externalType = externalTypes.get(eventClass);
        if (externalType == null) {
            throw new IllegalArgumentException("Unmapped Integration Event class: " + eventClass.getName());
        }
        return externalType;
    }

    @Override
    public Optional<Class<? extends IntegrationEvent>> eventClassFor(IntegrationEventDescriptor externalType) {
        Objects.requireNonNull(externalType, "External Integration Event type is required");
        return Optional.ofNullable(eventClasses.get(externalType));
    }

    /** Mutable construction step; {@link #build()} returns an immutable snapshot. */
    public static final class Builder {

        private final Map<Class<? extends IntegrationEvent>, IntegrationEventDescriptor> externalTypes =
                new LinkedHashMap<>();
        private final Map<IntegrationEventDescriptor, Class<? extends IntegrationEvent>> eventClasses =
                new LinkedHashMap<>();

        private Builder() {}

        public <E extends IntegrationEvent> Builder map(Class<E> eventClass, String eventType, int contractVersion) {
            Objects.requireNonNull(eventClass, "Integration Event class is required");
            IntegrationEventDescriptor externalType = new IntegrationEventDescriptor(eventType, contractVersion);
            if (externalTypes.containsKey(eventClass)) {
                throw new IllegalStateException("Duplicate Integration Event class mapping: " + eventClass.getName());
            }
            Class<? extends IntegrationEvent> existingClass = eventClasses.get(externalType);
            if (existingClass != null) {
                throw new IllegalStateException("Duplicate Integration Event type mapping: " + externalType.eventType()
                        + "/" + externalType.contractVersion());
            }
            externalTypes.put(eventClass, externalType);
            eventClasses.put(externalType, eventClass);
            return this;
        }

        public MapBasedIntegrationEventNameMapping build() {
            return new MapBasedIntegrationEventNameMapping(externalTypes, eventClasses);
        }
    }
}
