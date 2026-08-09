package com.flowzati.archone.messaging.api;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Stable subscription identity and delivery configuration.
 *
 * <p>{@code subscriberId} scopes Inbox idempotency. {@code consumerGroupId} controls broker
 * delivery. They are intentionally separate even when an application initially gives them the
 * same value.
 */
public record MessageSubscriptionConfiguration(
    String subscriberId,
    String consumerGroupId,
    Set<String> logicalChannels
) {

  public MessageSubscriptionConfiguration {
    if (isBlank(subscriberId) || isBlank(consumerGroupId)
        || logicalChannels == null || logicalChannels.isEmpty()
        || logicalChannels.stream().anyMatch(MessageSubscriptionConfiguration::isBlank)) {
      throw new IllegalArgumentException("Message subscription fields are required");
    }
    logicalChannels = Collections.unmodifiableSet(new LinkedHashSet<>(logicalChannels));
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
