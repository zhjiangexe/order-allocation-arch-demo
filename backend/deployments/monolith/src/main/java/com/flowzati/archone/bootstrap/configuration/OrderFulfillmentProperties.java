package com.flowzati.archone.bootstrap.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Selects the single fulfillment process driver enabled by this deployable. */
@ConfigurationProperties("archone.fulfillment")
public record OrderFulfillmentProperties(Driver orchestrationMode) {

    public OrderFulfillmentProperties {
        orchestrationMode = orchestrationMode == null ? Driver.EVENTS : orchestrationMode;
    }

    public enum Driver {
        EVENTS,
        TEMPORAL
    }
}
