package com.flowzati.archone.bootstrap.messaging.consumer;

import com.flowzati.archone.messaging.spring.consumer.kafka.KafkaConsumerFailurePolicyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Application-owned policies for order-promising Kafka consumers.
 *
 * <p>The messaging runtime owns container, DLT and observation wiring. This configuration only
 * states which application failures deserve delayed redelivery after their transaction rolls
 * back. Mapping, contract, business, and unknown programming failures go directly to DLT.
 */
@Configuration(proxyBeanMethods = false)
public class OrderPromisingKafkaConsumerConfiguration {

    @Bean
    public KafkaConsumerFailurePolicyResolver orderPromisingKafkaConsumerFailurePolicyResolver() {
        return KafkaConsumerFailurePolicyResolver.fixed(
                OrderPromisingConsumerFailurePolicy.kafkaConsumerFailurePolicy());
    }
}
