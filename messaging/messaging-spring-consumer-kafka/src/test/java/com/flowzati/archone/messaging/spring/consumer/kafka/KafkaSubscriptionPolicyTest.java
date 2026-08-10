package com.flowzati.archone.messaging.spring.consumer.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.listener.ContainerProperties;

class KafkaSubscriptionPolicyTest {

  @Test
  void exposesConservativeImmutableDefaults() {
    KafkaSubscriptionPolicy policy = KafkaSubscriptionPolicy.defaults();

    assertThat(policy.concurrency()).isOne();
    assertThat(policy.ackMode()).isEqualTo(ContainerProperties.AckMode.BATCH);
    assertThat(policy.missingTopicsFatal()).isTrue();
    assertThat(policy.observationEnabled()).isFalse();
    assertThat(policy.shutdownTimeout()).isEqualTo(Duration.ofSeconds(10));
    assertThat(policy.commonErrorHandler()).isEmpty();
  }

  @Test
  void enablesTransportObservationExplicitly() {
    assertThat(KafkaSubscriptionPolicy.builder()
        .observationEnabled(true)
        .build()
        .observationEnabled()).isTrue();
  }

  @Test
  void rejectsInvalidLifecycleSettings() {
    assertThatThrownBy(() -> KafkaSubscriptionPolicy.builder().concurrency(0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("concurrency");
    assertThatThrownBy(() -> KafkaSubscriptionPolicy.builder()
        .shutdownTimeout(Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("shutdown timeout");
  }
}
