package com.flowzati.archone.messaging.consumer.common;

import com.flowzati.archone.messaging.api.MessageHandler;
import com.flowzati.archone.messaging.api.MessageSubscription;

/** Single generic runtime SPI; Kafka-specific implementations must implement this contract. */
@FunctionalInterface
public interface MessageConsumerImplementation {

  MessageSubscription subscribe(
      ResolvedMessageSubscription subscription,
      MessageHandler handler
  );
}
