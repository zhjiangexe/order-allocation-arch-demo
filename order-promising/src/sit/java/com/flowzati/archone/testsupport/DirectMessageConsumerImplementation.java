package com.flowzati.archone.testsupport;

import com.flowzati.archone.messaging.api.Message;
import com.flowzati.archone.messaging.api.MessageContext;
import com.flowzati.archone.messaging.api.MessageHandler;
import com.flowzati.archone.messaging.api.MessageSubscription;
import com.flowzati.archone.messaging.consumer.common.MessageConsumerImplementation;
import com.flowzati.archone.messaging.consumer.common.ResolvedMessageSubscription;

/** One-subscription in-memory transport used by SITs that deliberately disable Kafka containers. */
final class DirectMessageConsumerImplementation implements MessageConsumerImplementation {

  private ResolvedMessageSubscription subscription;
  private MessageHandler handler;

  @Override
  public MessageSubscription subscribe(
      ResolvedMessageSubscription subscription,
      MessageHandler handler
  ) {
    if (this.subscription != null) {
      throw new IllegalStateException("Direct SIT transport accepts one subscription");
    }
    this.subscription = subscription;
    this.handler = handler;
    return new MessageSubscription() {
      private boolean running = true;

      @Override
      public boolean isRunning() {
        return running;
      }

      @Override
      public void stop() {
        running = false;
      }
    };
  }

  void emit(String destination, Message message) {
    if (subscription == null || handler == null) {
      throw new IllegalStateException("Direct SIT subscription has not started");
    }
    handler.handle(
        message,
        new MessageContext(
            subscription.subscriberId(),
            subscription.logicalChannelFor(destination),
            1));
  }
}
