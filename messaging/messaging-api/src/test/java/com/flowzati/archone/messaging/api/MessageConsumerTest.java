package com.flowzati.archone.messaging.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class MessageConsumerTest {

  @Test
  void exposesTheTramShapedBasicSubscribeMethod() throws NoSuchMethodException {
    Method subscribe = MessageConsumer.class.getMethod(
        "subscribe", String.class, Set.class, MessageHandler.class);

    assertThat(subscribe.getReturnType()).isEqualTo(MessageSubscription.class);
    assertThat(Modifier.isAbstract(subscribe.getModifiers())).isTrue();
  }

  @Test
  void exposesTheAdditiveSubscriptionOptionsOverload() throws NoSuchMethodException {
    Method subscribe = MessageConsumer.class.getMethod(
        "subscribe",
        String.class,
        Set.class,
        MessageHandler.class,
        MessageSubscriptionOptions.class);

    assertThat(subscribe.getReturnType()).isEqualTo(MessageSubscription.class);
    assertThat(Modifier.isAbstract(subscribe.getModifiers())).isTrue();
  }

  @SuppressWarnings("deprecation")
  @Test
  void gateBConfigurationOverloadDelegatesWithoutChangingEitherIdentity() {
    AtomicReference<String> subscriber = new AtomicReference<>();
    AtomicReference<MessageSubscriptionOptions> captured = new AtomicReference<>();
    MessageConsumer consumer = recordingConsumer(subscriber, captured);

    consumer.subscribe(
        new MessageSubscriptionConfiguration(
            "allocation-inbox", "allocation-kafka", Set.of("order-events")),
        (message, context) -> { });

    assertThat(subscriber).hasValue("allocation-inbox");
    assertThat(captured.get().resolveConsumerGroupId(subscriber.get()))
        .isEqualTo("allocation-kafka");
  }

  private MessageConsumer recordingConsumer(
      AtomicReference<String> subscriber,
      AtomicReference<MessageSubscriptionOptions> captured
  ) {
    return new MessageConsumer() {
      @Override
      public MessageSubscription subscribe(
          String subscriberId,
          Set<String> channels,
          MessageHandler handler
      ) {
        subscriber.set(subscriberId);
        captured.set(MessageSubscriptionOptions.defaults());
        return subscription();
      }

      @Override
      public MessageSubscription subscribe(
          String subscriberId,
          Set<String> channels,
          MessageHandler handler,
          MessageSubscriptionOptions options
      ) {
        subscriber.set(subscriberId);
        captured.set(options);
        return subscription();
      }
    };
  }

  private MessageSubscription subscription() {
    return new MessageSubscription() {
      @Override
      public boolean isRunning() {
        return true;
      }

      @Override
      public void stop() {
      }
    };
  }
}
