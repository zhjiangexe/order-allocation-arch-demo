package com.flowzati.archone.messaging.consumer.common;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Physical destinations plus their unambiguous local logical-channel identity. */
public record ResolvedMessageSubscription(
    String subscriberId,
    String consumerGroupId,
    Map<String, String> destinationToLogicalChannel
) {

  public ResolvedMessageSubscription {
    if (isBlank(subscriberId) || isBlank(consumerGroupId)
        || destinationToLogicalChannel == null || destinationToLogicalChannel.isEmpty()) {
      throw new IllegalArgumentException("Resolved subscription fields are required");
    }
    LinkedHashMap<String, String> copy = new LinkedHashMap<>();
    destinationToLogicalChannel.forEach((destination, logicalChannel) -> {
      if (isBlank(destination) || isBlank(logicalChannel)) {
        throw new IllegalArgumentException("Resolved subscription channels are required");
      }
      copy.put(destination, logicalChannel);
    });
    destinationToLogicalChannel = Collections.unmodifiableMap(copy);
  }

  public String logicalChannelFor(String destination) {
    String logicalChannel = destinationToLogicalChannel.get(destination);
    if (logicalChannel == null) {
      throw new IllegalArgumentException("Unexpected subscription destination: " + destination);
    }
    return logicalChannel;
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
