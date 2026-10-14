package com.flowzati.archone.bootstrap.microservice;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/** This deployment currently assembles event-driven services without Temporal workers. */
@Configuration(proxyBeanMethods = false)
public class MicroserviceModeConfiguration {
    public MicroserviceModeConfiguration(@Value("${archone.fulfillment.orchestration-mode:events}") String mode) {
        if (!"events".equals(mode)) {
            throw new IllegalStateException(
                    "Microservice deployment currently requires archone.fulfillment.orchestration-mode=events");
        }
    }
}
