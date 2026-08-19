package com.flowzati.archone.bootstrap.fulfillment;

import java.util.Locale;

/** 此 deployable 使用哪一個流程 driver；兩種模式仍共用相同 application use cases。 */
public enum FulfillmentOrchestrationMode {
    EVENTS,
    TEMPORAL;

    public static FulfillmentOrchestrationMode parse(String configuredValue) {
        if (configuredValue == null || configuredValue.isBlank()) {
            throw new IllegalArgumentException("Fulfillment orchestration mode is required");
        }
        try {
            return valueOf(configuredValue.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            throw new IllegalArgumentException(
                    "Unsupported fulfillment orchestration mode: " + configuredValue + "; expected events or temporal");
        }
    }
}
