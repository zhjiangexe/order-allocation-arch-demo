package com.flowzati.archone.messaging.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowzati.archone.messaging.api.Message;
import com.flowzati.archone.messaging.api.MessageBuilder;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EventMessageHeadersTest {

  @Test
  void treatsAMissingLegacyContractVersionAsVersionOne() {
    Message legacy = eventMessageBuilder().build();

    assertThat(EventMessageHeaders.contractVersion(legacy)).isOne();
    assertThat(EventMessageHeaders.eventType(legacy)).isEqualTo("order-placed.v1");
  }

  @Test
  void rejectsConflictingGenericAndEventTypes() {
    Message conflicting = eventMessageBuilder()
        .withHeader(EventMessageHeaders.EVENT_TYPE, "another-event.v1")
        .build();

    assertThatThrownBy(() -> EventMessageHeaders.eventType(conflicting))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Message type does not match event type");
  }

  private MessageBuilder eventMessageBuilder() {
    return MessageBuilder.withPayload("{}")
        .withId(UUID.randomUUID())
        .withType("order-placed.v1")
        .withPartitionId("order-1")
        .withMessageDate(Instant.parse("2026-08-09T08:00:00Z"))
        .withHeader(EventMessageHeaders.EVENT_TYPE, "order-placed.v1")
        .withHeader(EventMessageHeaders.EVENT_AGGREGATE_TYPE, "Order")
        .withHeader(EventMessageHeaders.EVENT_AGGREGATE_ID, "order-1");
  }
}
