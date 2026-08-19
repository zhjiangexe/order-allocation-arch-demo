package com.flowzati.archone.messaging.spring.consumer.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class KafkaContainerIdentityTest {

    @Test
    void identityIsStableAndKeepsSubscriberAndGroupBoundariesUnambiguous() {
        assertThat(KafkaContainerIdentity.from("subscriber-a", "group-a"))
                .isEqualTo(KafkaContainerIdentity.from("subscriber-a", "group-a"));
        assertThat(KafkaContainerIdentity.from("subscriber-a", "group-a"))
                .isNotEqualTo(KafkaContainerIdentity.from("subscriber", "a-group-a"));
    }
}
