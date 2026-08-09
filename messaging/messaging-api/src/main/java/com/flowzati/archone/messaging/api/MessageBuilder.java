package com.flowzati.archone.messaging.api;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Builds immutable {@link Message} values without exposing a mutable header map. */
public final class MessageBuilder {

  private final String payload;
  private final LinkedHashMap<String, String> headers = new LinkedHashMap<>();

  private MessageBuilder(String payload) {
    this.payload = payload;
  }

  public static MessageBuilder withPayload(String payload) {
    return new MessageBuilder(payload);
  }

  public static MessageBuilder from(Message message) {
    if (message == null) {
      throw new IllegalArgumentException("Message is required");
    }
    return withPayload(message.payload()).withHeaders(message.headers());
  }

  public MessageBuilder withId(UUID id) {
    if (id == null) {
      throw new IllegalArgumentException("Message ID is required");
    }
    return withHeader(MessageHeaders.MESSAGE_ID, id.toString());
  }

  public MessageBuilder withType(String type) {
    return withHeader(MessageHeaders.MESSAGE_TYPE, type);
  }

  public MessageBuilder withPartitionId(String partitionId) {
    return withHeader(MessageHeaders.PARTITION_ID, partitionId);
  }

  public MessageBuilder withMessageDate(Instant messageDate) {
    if (messageDate == null) {
      throw new IllegalArgumentException("Message date is required");
    }
    return withHeader(MessageHeaders.MESSAGE_DATE, messageDate.toString());
  }

  public MessageBuilder withHeader(String name, String value) {
    MessageHeaders.validate(name, value);
    headers.put(name, value);
    return this;
  }

  public MessageBuilder withHeaders(Map<String, String> additionalHeaders) {
    if (additionalHeaders == null) {
      throw new IllegalArgumentException("Additional headers are required");
    }
    additionalHeaders.forEach(this::withHeader);
    return this;
  }

  public Message build() {
    return new Message(payload, headers);
  }
}
