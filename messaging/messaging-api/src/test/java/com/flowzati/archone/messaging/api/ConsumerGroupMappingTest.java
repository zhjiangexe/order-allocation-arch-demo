package com.flowzati.archone.messaging.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ConsumerGroupMappingTest {

  @Test
  void usesExplicitMappingsWithIdentityFallback() {
    ConsumerGroupMapping mapping = new MapBasedConsumerGroupMapping(
        Map.of("allocation", "allocation-v2"));

    assertThat(mapping.transform("allocation")).isEqualTo("allocation-v2");
    assertThat(mapping.transform("ordering")).isEqualTo("ordering");
    assertThat(IdentityConsumerGroupMapping.INSTANCE.transform("ordering"))
        .isEqualTo("ordering");
  }

  @Test
  void rejectsBlankSubscriberOrGroupIdentities() {
    assertThatThrownBy(() -> new MapBasedConsumerGroupMapping(Map.of("allocation", " ")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Subscriber and consumer group IDs are required");
    assertThatThrownBy(() -> IdentityConsumerGroupMapping.INSTANCE.transform(" "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Subscriber ID is required");
  }
}
