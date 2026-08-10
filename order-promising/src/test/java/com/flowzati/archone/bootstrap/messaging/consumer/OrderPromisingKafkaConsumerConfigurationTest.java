package com.flowzati.archone.bootstrap.messaging.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowzati.archone.messaging.consumer.common.ResolvedMessageSubscription;
import com.flowzati.archone.messaging.spring.consumer.kafka.KafkaConsumerFailurePolicy;
import com.flowzati.archone.messaging.spring.optimisticlocking.OptimisticLockingRetryExhaustedException;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.util.backoff.BackOffExecution;

class OrderPromisingKafkaConsumerConfigurationTest {

  @Test
  void retriesOnlyExhaustedOptimisticLocking() {
    KafkaConsumerFailurePolicy policy = policy();

    assertThat(policy.failureClassifier().classify(exhausted()).retryable()).isTrue();
    assertThat(policy.failureClassifier()
        .classify(new IllegalArgumentException("invalid contract"))
        .retryable()).isFalse();
  }

  @Test
  void retainsFourExponentialContainerRetries() {
    BackOffExecution execution = policy().retryBackOff().start();

    assertThat(execution.nextBackOff()).isEqualTo(1_000);
    assertThat(execution.nextBackOff()).isEqualTo(2_000);
    assertThat(execution.nextBackOff()).isEqualTo(4_000);
    assertThat(execution.nextBackOff()).isEqualTo(8_000);
    assertThat(execution.nextBackOff()).isEqualTo(BackOffExecution.STOP);
  }

  private KafkaConsumerFailurePolicy policy() {
    return new OrderPromisingKafkaConsumerConfiguration()
        .orderPromisingKafkaConsumerFailurePolicyResolver()
        .resolve(new ResolvedMessageSubscription(
            "any-order-promising-subscriber",
            "any-order-promising-group",
            Map.of("any.topic", "any-channel")));
  }

  private OptimisticLockingRetryExhaustedException exhausted() {
    return new OptimisticLockingRetryExhaustedException(
        "allocation-ordering-events",
        UUID.randomUUID(),
        3,
        new RuntimeException("optimistic lock conflict"));
  }

}
