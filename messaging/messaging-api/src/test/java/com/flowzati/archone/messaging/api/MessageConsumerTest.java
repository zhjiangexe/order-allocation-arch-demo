package com.flowzati.archone.messaging.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Set;
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
}
