package com.flowzati.archone.testsupport;

import com.flowzati.archone.messaging.testsupport.ControllableMessageConsumerImplementation;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** Replaces Kafka transport while retaining the production consumer wiring and decorator chain. */
@TestConfiguration(proxyBeanMethods = false)
public class InProcessMessagingTestConfiguration {

    @Bean
    ControllableMessageConsumerImplementation controllableMessageConsumerImplementation() {
        return new ControllableMessageConsumerImplementation();
    }
}
