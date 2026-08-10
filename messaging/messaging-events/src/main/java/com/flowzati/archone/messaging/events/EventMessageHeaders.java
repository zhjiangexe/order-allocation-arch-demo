package com.flowzati.archone.messaging.events;

import com.flowzati.archone.messaging.api.Message;
import com.flowzati.archone.messaging.api.MessageHeaders;

/** Canonical generic-message headers owned by the Integration Event protocol. */
public final class EventMessageHeaders {

  public static final String EVENT_TYPE = "event-type";
  public static final String EVENT_AGGREGATE_TYPE = "event-aggregate-type";
  public static final String EVENT_AGGREGATE_ID = "event-aggregate-id";
  public static final String EVENT_CONTRACT_VERSION = "event-contract-version";
  public static final int INITIAL_CONTRACT_VERSION = 1;

  private EventMessageHeaders() {
  }

  public static String eventType(Message message) {
    String eventType;
    try {
      eventType = message.requiredHeader(EVENT_TYPE);
    } catch (IllegalArgumentException exception) {
      throw new IntegrationEventContractException(exception.getMessage(), exception);
    }
    if (!message.type().equals(eventType)) {
      throw new IntegrationEventContractException("Message type does not match event type");
    }
    return eventType;
  }

  public static int contractVersion(Message message) {
    String value = message.header(EVENT_CONTRACT_VERSION)
        .orElse(Integer.toString(INITIAL_CONTRACT_VERSION));
    try {
      int version = Integer.parseInt(value);
      if (version < 1) {
        throw new NumberFormatException("version must be positive");
      }
      return version;
    } catch (NumberFormatException exception) {
      throw new IntegrationEventContractException(
          "Invalid event-contract-version header: " + value,
          exception);
    }
  }

  public static void validateForPublication(Message message) {
    MessageHeaders.require(
        message,
        MessageHeaders.MESSAGE_ID,
        MessageHeaders.MESSAGE_TYPE,
        MessageHeaders.PARTITION_ID,
        MessageHeaders.MESSAGE_DATE,
        MessageHeaders.CONTENT_TYPE,
        EVENT_TYPE,
        EVENT_AGGREGATE_TYPE,
        EVENT_AGGREGATE_ID,
        EVENT_CONTRACT_VERSION);
    eventType(message);
    contractVersion(message);
  }
}
