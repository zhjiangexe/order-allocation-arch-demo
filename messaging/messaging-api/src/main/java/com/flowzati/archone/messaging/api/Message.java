package com.flowzati.archone.messaging.api;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Framework-neutral immutable message envelope.
 *
 * <p>The base contract deliberately knows nothing about Integration Events, Commands, JDBC, or
 * Kafka. Protocol-specific metadata is represented by headers owned by the corresponding protocol
 * module. The first transport version is JSON-only, so an omitted content type is normalized to
 * {@code application/json}.
 */
public record Message(String payload, Map<String, String> headers) {

  public Message {
    if (payload == null || payload.isBlank()) {
      throw new IllegalArgumentException("Message payload is required");
    }
    if (headers == null) {
      throw new IllegalArgumentException("Message headers are required");
    }

    LinkedHashMap<String, String> normalized = new LinkedHashMap<>();
    headers.forEach((name, value) -> {
      MessageHeaders.validate(name, value);
      normalized.put(name, value);
    });
    normalized.putIfAbsent(MessageHeaders.CONTENT_TYPE, MessageHeaders.APPLICATION_JSON);
    if (!MessageHeaders.APPLICATION_JSON.equals(normalized.get(MessageHeaders.CONTENT_TYPE))) {
      throw new IllegalArgumentException(
          "Unsupported message content type: " + normalized.get(MessageHeaders.CONTENT_TYPE));
    }
    headers = Collections.unmodifiableMap(normalized);
  }

  /** Returns the canonical UUID stored only in the required {@code message-id} header. */
  public UUID id() {
    String value = requiredHeader(MessageHeaders.MESSAGE_ID);
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("Invalid message-id header: " + value, exception);
    }
  }

  public String type() {
    return requiredHeader(MessageHeaders.MESSAGE_TYPE);
  }

  public String partitionId() {
    return requiredHeader(MessageHeaders.PARTITION_ID);
  }

  public Instant messageDate() {
    String value = requiredHeader(MessageHeaders.MESSAGE_DATE);
    try {
      return Instant.parse(value);
    } catch (DateTimeParseException exception) {
      throw new IllegalArgumentException("Invalid message-date header: " + value, exception);
    }
  }

  public Optional<String> header(String name) {
    MessageHeaders.validateName(name);
    return Optional.ofNullable(headers.get(name));
  }

  public String requiredHeader(String name) {
    return header(name)
        .filter(value -> !value.isBlank())
        .orElseThrow(() -> new IllegalArgumentException("Missing message header: " + name));
  }

  /** Returns a new envelope; this instance and its header map remain unchanged. */
  public Message withHeader(String name, String value) {
    return MessageBuilder.from(this).withHeader(name, value).build();
  }

  public static MessageBuilder builder(String payload) {
    return MessageBuilder.withPayload(payload);
  }
}
