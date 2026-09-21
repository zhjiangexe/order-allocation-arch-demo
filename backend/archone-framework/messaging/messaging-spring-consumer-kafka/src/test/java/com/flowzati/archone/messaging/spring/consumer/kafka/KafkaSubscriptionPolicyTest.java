package com.flowzati.archone.messaging.spring.consumer.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class KafkaSubscriptionPolicyTest {

    @Test
    void defaultPolicyInheritsEveryOperationalSettingFromTheSharedFactory() {
        KafkaSubscriptionPolicy policy = KafkaSubscriptionPolicy.defaults();

        assertThat(policy.concurrencyOverride()).isEmpty();
        assertThat(policy.ackModeOverride()).isEmpty();
        assertThat(policy.missingTopicsFatalOverride()).isEmpty();
        assertThat(policy.observationEnabledOverride()).isEmpty();
        assertThat(policy.autoStartupOverride()).isEmpty();
        assertThat(policy.shutdownTimeoutOverride()).isEmpty();
        assertThat(policy.commonErrorHandler()).isEmpty();
    }

    @Test
    void enablesTransportObservationExplicitly() {
        assertThat(KafkaSubscriptionPolicy.builder()
                        .observationEnabled(true)
                        .build()
                        .observationEnabledOverride())
                .contains(true);
    }

    @Test
    void canRegisterASubscriptionWithoutStartingItsContainer() {
        assertThat(KafkaSubscriptionPolicy.builder().autoStartup(false).build().autoStartupOverride())
                .contains(false);
    }

    @Test
    void rejectsInvalidLifecycleSettings() {
        assertThatThrownBy(() -> KafkaSubscriptionPolicy.builder().concurrency(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("concurrency");
        assertThatThrownBy(() -> KafkaSubscriptionPolicy.builder().shutdownTimeout(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("shutdown timeout");
    }
}
