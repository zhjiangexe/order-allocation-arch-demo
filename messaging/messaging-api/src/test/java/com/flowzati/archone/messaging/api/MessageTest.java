package com.flowzati.archone.messaging.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MessageTest {

  @Test
  void keepsIdentityOnlyInAnImmutableHeaderEnvelope() {
    UUID id = UUID.randomUUID();
    LinkedHashMap<String, String> callerHeaders = new LinkedHashMap<>(Map.of(
        MessageHeaders.MESSAGE_ID, id.toString(),
        MessageHeaders.MESSAGE_TYPE, "example.v1"));

    Message message = new Message("{}", callerHeaders);
    callerHeaders.put(MessageHeaders.MESSAGE_ID, UUID.randomUUID().toString());

    assertThat(message.id()).isEqualTo(id);
    assertThat(message.type()).isEqualTo("example.v1");
    assertThat(message.headers())
        .containsEntry(MessageHeaders.CONTENT_TYPE, MessageHeaders.APPLICATION_JSON);
    assertThatThrownBy(() -> message.headers().put("custom", "value"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void returnsANewMessageWhenAddingAHeader() {
    Message original = MessageBuilder.withPayload("{}")
        .withId(UUID.randomUUID())
        .withType("example.v1")
        .build();

    Message enriched = original.withHeader(MessageHeaders.CORRELATION_ID, "conversation-1");

    assertThat(original.header(MessageHeaders.CORRELATION_ID)).isEmpty();
    assertThat(enriched.header(MessageHeaders.CORRELATION_ID)).contains("conversation-1");
  }

  @Test
  void exposesTypedPartitionAndDateHeaders() {
    Instant date = Instant.parse("2026-08-09T08:00:00Z");
    Message message = MessageBuilder.withPayload("{}")
        .withId(UUID.randomUUID())
        .withType("example.v1")
        .withPartitionId("order-1")
        .withMessageDate(date)
        .build();

    assertThat(message.partitionId()).isEqualTo("order-1");
    assertThat(message.messageDate()).isEqualTo(date);
  }

  @Test
  void failsFastForMissingOrInvalidRequiredHeaders() {
    Message missing = MessageBuilder.withPayload("{}").build();
    Message invalid = MessageBuilder.withPayload("{}")
        .withHeader(MessageHeaders.MESSAGE_ID, "not-a-uuid")
        .build();

    assertThatThrownBy(missing::id)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Missing message header: message-id");
    assertThatThrownBy(invalid::id)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Invalid message-id header: not-a-uuid");
  }

  @Test
  void rejectsInvalidHeadersAndUnsupportedContentTypes() {
    assertThatThrownBy(() -> new Message("{}", Map.of("Invalid Header", "value")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Invalid message header name: Invalid Header");
    assertThatThrownBy(() -> MessageBuilder.withPayload("{}")
        .withHeader(MessageHeaders.CONTENT_TYPE, "application/protobuf")
        .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Unsupported message content type: application/protobuf");
  }
}
