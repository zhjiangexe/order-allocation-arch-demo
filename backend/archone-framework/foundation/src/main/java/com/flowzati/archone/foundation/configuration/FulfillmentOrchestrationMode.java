package com.flowzati.archone.foundation.configuration;

/**
 * Temporary deployment-wide compatibility switch while both fulfillment orchestration models are supported.
 * Remove this contract when one model is retired.
 */
public final class FulfillmentOrchestrationMode {

    public static final String ORCHESTRATION_MODE = "archone.fulfillment.orchestration-mode";
    public static final String EVENTS = "events";
    public static final String TEMPORAL = "temporal";

    public enum Driver {
        EVENTS(FulfillmentOrchestrationMode.EVENTS),
        TEMPORAL(FulfillmentOrchestrationMode.TEMPORAL);

        private final String propertyValue;

        Driver(String propertyValue) {
            this.propertyValue = propertyValue;
        }

        public String propertyValue() {
            return propertyValue;
        }

        public boolean isEvents() {
            return this == EVENTS;
        }
    }

    private FulfillmentOrchestrationMode() {}
}
