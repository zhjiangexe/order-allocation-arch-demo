package com.flowzati.archone.messaging.spring.consumer.kafka;

import com.flowzati.archone.messaging.consumer.common.ResolvedMessageSubscription;
import java.util.Objects;

/** Resolves operational policy without putting Kafka settings in the generic consumer API. */
@FunctionalInterface
public interface KafkaSubscriptionPolicyResolver {

  KafkaSubscriptionPolicy resolve(ResolvedMessageSubscription subscription);

  static KafkaSubscriptionPolicyResolver fixed(KafkaSubscriptionPolicy policy) {
    Objects.requireNonNull(policy, "Kafka subscription policy is required");
    return subscription -> policy;
  }
}
