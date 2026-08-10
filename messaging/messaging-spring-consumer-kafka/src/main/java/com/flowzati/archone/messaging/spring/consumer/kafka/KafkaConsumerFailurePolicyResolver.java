package com.flowzati.archone.messaging.spring.consumer.kafka;

import com.flowzati.archone.messaging.consumer.common.ResolvedMessageSubscription;
import java.util.Objects;

/** Resolves application retry semantics independently from container lifecycle settings. */
@FunctionalInterface
public interface KafkaConsumerFailurePolicyResolver {

  KafkaConsumerFailurePolicy resolve(ResolvedMessageSubscription subscription);

  static KafkaConsumerFailurePolicyResolver fixed(KafkaConsumerFailurePolicy policy) {
    Objects.requireNonNull(policy, "Kafka consumer failure policy is required");
    return subscription -> {
      Objects.requireNonNull(subscription, "Resolved message subscription is required");
      return policy;
    };
  }
}
