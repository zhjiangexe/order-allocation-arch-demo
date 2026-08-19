package com.flowzati.archone.bootstrap.messaging.consumer;

import com.flowzati.archone.messaging.spring.consumer.kafka.KafkaConsumerFailurePolicyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Application-owned policies for Kafka consumers assembled by the bootstrap module.
 *
 * <p>The messaging runtime owns container, DLT and observation wiring. This configuration only
 * states which application failures deserve delayed redelivery after their transaction rolls
 * back. Mapping, contract, business, and unknown programming failures go directly to DLT.
 */
@Configuration(proxyBeanMethods = false)
public class BootstrapKafkaConsumerConfiguration {

    @Bean
    public KafkaConsumerFailurePolicyResolver bootstrapKafkaConsumerFailurePolicyResolver() {
        return KafkaConsumerFailurePolicyResolver.fixed(BootstrapConsumerFailurePolicy.kafkaConsumerFailurePolicy());
    }
}
