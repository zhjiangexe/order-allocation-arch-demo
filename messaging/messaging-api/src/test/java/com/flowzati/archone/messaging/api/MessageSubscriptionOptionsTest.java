package com.flowzati.archone.messaging.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MessageSubscriptionOptionsTest {

    @Test
    void defaultsTheConsumerGroupToTheStableSubscriberId() {
        MessageSubscriptionOptions options = MessageSubscriptionOptions.defaults();

        assertThat(options.consumerGroupId()).isEmpty();
        assertThat(options.resolveConsumerGroupId("stock-allocation")).isEqualTo("stock-allocation");
    }

    @Test
    void keepsAnExplicitConsumerGroupSeparate() {
        MessageSubscriptionOptions options = MessageSubscriptionOptions.builder()
                .consumerGroupId("stock-allocation-kafka")
                .build();

        assertThat(options.consumerGroupId()).contains("stock-allocation-kafka");
        assertThat(options.resolveConsumerGroupId("stock-allocation-inbox")).isEqualTo("stock-allocation-kafka");
    }

    @Test
    void rejectsBlankSubscriberAndConsumerGroupIdentities() {
        assertThatThrownBy(() -> MessageSubscriptionOptions.withConsumerGroupId(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Message consumer group ID is required");
        assertThatThrownBy(() -> MessageSubscriptionOptions.defaults().resolveConsumerGroupId(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Message subscriber ID is required");
    }
}
