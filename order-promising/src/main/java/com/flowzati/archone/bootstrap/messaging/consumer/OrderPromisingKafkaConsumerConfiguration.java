package com.flowzati.archone.bootstrap.messaging.consumer;

import com.flowzati.archone.messaging.consumer.common.MessageFailureCategory;
import com.flowzati.archone.messaging.consumer.common.MessageFailureClassification;
import com.flowzati.archone.messaging.consumer.common.TypeBasedMessageFailureClassifier;
import com.flowzati.archone.messaging.spring.consumer.kafka.KafkaConsumerFailurePolicy;
import com.flowzati.archone.messaging.spring.consumer.kafka.KafkaConsumerFailurePolicyResolver;
import com.flowzati.archone.messaging.spring.optimisticlocking.OptimisticLockingRetryExhaustedException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

/**
 * Application-owned policies for order-promising Kafka consumers.
 *
 * <p>The messaging runtime owns container, DLT and observation wiring. This configuration only
 * states that persistent optimistic-lock contention deserves delayed redelivery; every other
 * failure goes directly to DLT after its transactional message attempt fails.
 */
@Configuration(proxyBeanMethods = false)
public class OrderPromisingKafkaConsumerConfiguration {

  @Bean
  public KafkaConsumerFailurePolicyResolver orderPromisingKafkaConsumerFailurePolicyResolver() {
    ExponentialBackOffWithMaxRetries retryBackOff = new ExponentialBackOffWithMaxRetries(4);
    retryBackOff.setInitialInterval(1_000);
    retryBackOff.setMultiplier(2.0);
    retryBackOff.setMaxInterval(10_000);

    TypeBasedMessageFailureClassifier failureClassifier =
        TypeBasedMessageFailureClassifier.builder()
            .retryable(
                OptimisticLockingRetryExhaustedException.class,
                MessageFailureCategory.HANDLER)
            .fallback(MessageFailureClassification.nonRetryable(
                MessageFailureCategory.HANDLER))
            .build();
    return KafkaConsumerFailurePolicyResolver.fixed(
        new KafkaConsumerFailurePolicy(retryBackOff, failureClassifier));
  }
}
