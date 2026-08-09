package com.flowzati.archone.messaging.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MessageSubscriptionConfigurationTest {

  @Test
  void keepsInboxAndBrokerIdentitiesExplicitlySeparate() {
    LinkedHashSet<String> channels = new LinkedHashSet<>(Set.of("order-events"));
    MessageSubscriptionConfiguration configuration = new MessageSubscriptionConfiguration(
        "allocation-inbox-scope", "allocation-kafka-group", channels);
    channels.add("mutated-after-construction");

    assertThat(configuration.subscriberId()).isEqualTo("allocation-inbox-scope");
    assertThat(configuration.consumerGroupId()).isEqualTo("allocation-kafka-group");
    assertThat(configuration.logicalChannels()).containsExactly("order-events");
    assertThatThrownBy(() -> configuration.logicalChannels().add("another"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void rejectsAnIncompleteSubscription() {
    assertThatThrownBy(() -> new MessageSubscriptionConfiguration(
        "subscriber", " ", Set.of("order-events")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Message subscription fields are required");
  }
}
