package com.flowzati.archone.messaging.observation;

import io.micrometer.observation.ObservationConvention;

/** Customization point for consumer observation naming and tags. */
public interface ConsumerMessageObservationConvention
        extends ObservationConvention<ConsumerMessageObservationContext> {}
