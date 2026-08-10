package com.flowzati.archone.messaging.spring.consumer.kafka;

import com.flowzati.archone.messaging.consumer.common.ResolvedMessageSubscription;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Exact physical-destination lookup shared by DLT and transport observation adapters. */
final class ResolvedKafkaSubscriptionIndex {

  private final Map<String, ResolvedMessageSubscription> subscriptionsByDestination;
  private final String owner;

  private ResolvedKafkaSubscriptionIndex(
      Map<String, ResolvedMessageSubscription> subscriptionsByDestination,
      String owner
  ) {
    this.subscriptionsByDestination = subscriptionsByDestination;
    this.owner = owner;
  }

  static ResolvedKafkaSubscriptionIndex create(
      Collection<ResolvedMessageSubscription> subscriptions,
      String owner
  ) {
    if (subscriptions == null || subscriptions.isEmpty()
        || subscriptions.stream().anyMatch(Objects::isNull)) {
      throw new IllegalArgumentException("Resolved message subscriptions are required");
    }
    if (owner == null || owner.isBlank()) {
      throw new IllegalArgumentException("Kafka subscription lookup owner is required");
    }

    Map<String, ResolvedMessageSubscription> indexed = new LinkedHashMap<>();
    subscriptions.forEach(subscription ->
        subscription.destinationToLogicalChannel().keySet().forEach(destination -> {
          ResolvedMessageSubscription previous = indexed.putIfAbsent(destination, subscription);
          if (previous != null) {
            throw new IllegalArgumentException(
                owner + " destination belongs to multiple subscribers: " + destination);
          }
        }));
    return new ResolvedKafkaSubscriptionIndex(Map.copyOf(indexed), owner);
  }

  ResolvedMessageSubscription resolve(String destination) {
    ResolvedMessageSubscription subscription = subscriptionsByDestination.get(destination);
    if (subscription == null) {
      throw new IllegalArgumentException(
          "No " + owner + " subscription metadata for destination: " + destination);
    }
    return subscription;
  }
}
