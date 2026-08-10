package com.flowzati.archone.messaging.spring.consumer.kafka;

import com.flowzati.archone.messaging.consumer.common.ResolvedMessageSubscription;
import java.util.Optional;
import org.springframework.kafka.listener.CommonErrorHandler;

/** Creates transport recovery behavior from the exact programmatic subscription identity. */
@FunctionalInterface
public interface KafkaSubscriptionErrorHandlerFactory {

  Optional<CommonErrorHandler> create(ResolvedMessageSubscription subscription);

  static KafkaSubscriptionErrorHandlerFactory none() {
    return subscription -> Optional.empty();
  }
}
