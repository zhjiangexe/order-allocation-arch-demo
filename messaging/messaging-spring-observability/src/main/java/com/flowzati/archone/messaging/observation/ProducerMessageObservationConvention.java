package com.flowzati.archone.messaging.observation;

import io.micrometer.observation.ObservationConvention;

/** Customization point for producer observation naming and tags. */
public interface ProducerMessageObservationConvention
    extends ObservationConvention<ProducerMessageObservationContext> {
}
