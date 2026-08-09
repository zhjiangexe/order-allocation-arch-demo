package com.flowzati.archone.messaging.consumer.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowzati.archone.messaging.api.MapBasedChannelMapping;
import com.flowzati.archone.messaging.api.Message;
import com.flowzati.archone.messaging.api.MessageBuilder;
import com.flowzati.archone.messaging.api.MessageContext;
import com.flowzati.archone.messaging.api.MessageHandler;
import com.flowzati.archone.messaging.api.MessageSubscription;
import com.flowzati.archone.messaging.api.MessageSubscriptionConfiguration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class MessageConsumerImplTest {

  @Test
  void mapsLogicalChannelsAndKeepsSubscriberAndGroupSeparate() {
    CapturingImplementation implementation = new CapturingImplementation();
    MessageConsumerImpl consumer = new MessageConsumerImpl(
        implementation,
        new MapBasedChannelMapping(Map.of("order-events", "prod.order-events")),
        List.of());
    AtomicReference<MessageContext> handledContext = new AtomicReference<>();

    MessageSubscription handle = consumer.subscribe(
        new MessageSubscriptionConfiguration(
            "allocation-inbox-scope", "allocation-kafka-group", Set.of("order-events")),
        (message, context) -> handledContext.set(context));

    assertThat(implementation.subscription.subscriberId()).isEqualTo("allocation-inbox-scope");
    assertThat(implementation.subscription.consumerGroupId()).isEqualTo("allocation-kafka-group");
    assertThat(implementation.subscription.destinationToLogicalChannel())
        .containsExactlyEntriesOf(Map.of("prod.order-events", "order-events"));
    assertThat(handle.isRunning()).isTrue();

    implementation.emit("prod.order-events", message(), 2);

    assertThat(handledContext.get()).isEqualTo(
        new MessageContext("allocation-inbox-scope", "order-events", 2));
    handle.stop();
    handle.stop();
    assertThat(handle.isRunning()).isFalse();
  }

  @Test
  void appliesTheDecoratorChainBeforeTheApplicationHandler() {
    CapturingImplementation implementation = new CapturingImplementation();
    List<String> calls = new ArrayList<>();
    MessageConsumerImpl consumer = new MessageConsumerImpl(
        implementation,
        logicalChannel -> logicalChannel,
        List.of(decorator(200, "inner", calls), decorator(100, "outer", calls)));

    consumer.subscribe(configuration(), (message, context) -> calls.add("handler"));
    implementation.emit("order-events", message(), 1);

    assertThat(calls).containsExactly(
        "outer.before", "inner.before", "handler", "inner.after.PROCESSED",
        "outer.after.PROCESSED");
  }

  @Test
  void rejectsAnAmbiguousMappingBeforeStartingTheRuntime() {
    CapturingImplementation implementation = new CapturingImplementation();
    MessageConsumerImpl consumer = new MessageConsumerImpl(
        implementation,
        logicalChannel -> "shared-topic",
        List.of());
    MessageSubscriptionConfiguration configuration = new MessageSubscriptionConfiguration(
        "subscriber", "group", Set.of("orders", "stock"));

    assertThatThrownBy(() -> consumer.subscribe(configuration, (message, context) -> { }))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Multiple logical channels map to the same destination: shared-topic");
    assertThat(implementation.subscription).isNull();
  }

  @Test
  void rejectsAContextThatDoesNotBelongToTheLocalSubscription() {
    CapturingImplementation implementation = new CapturingImplementation();
    MessageConsumerImpl consumer = new MessageConsumerImpl(implementation);
    consumer.subscribe(configuration(), (message, context) -> { });

    assertThatThrownBy(() -> implementation.handler.handle(
        message(), new MessageContext("another-subscriber", "order-events", 1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Message context subscriber does not match subscription");
  }

  private MessageHandlerDecorator decorator(int order, String name, List<String> calls) {
    return new MessageHandlerDecorator() {
      @Override
      public int order() {
        return order;
      }

      @Override
      public ProcessingOutcome handle(
          MessageHandlerInvocation invocation,
          MessageHandlerDecoratorChain chain
      ) {
        calls.add(name + ".before");
        ProcessingOutcome outcome = chain.invokeNext(invocation);
        calls.add(name + ".after." + outcome);
        return outcome;
      }
    };
  }

  private MessageSubscriptionConfiguration configuration() {
    return new MessageSubscriptionConfiguration(
        "allocation-inbox-scope", "allocation-kafka-group", Set.of("order-events"));
  }

  private Message message() {
    return MessageBuilder.withPayload("{}")
        .withId(UUID.randomUUID())
        .withType("example.v1")
        .withPartitionId("order-1")
        .build();
  }

  private static final class CapturingImplementation implements MessageConsumerImplementation {
    private ResolvedMessageSubscription subscription;
    private MessageHandler handler;
    private final TestSubscription handle = new TestSubscription();

    @Override
    public MessageSubscription subscribe(
        ResolvedMessageSubscription subscription,
        MessageHandler handler
    ) {
      this.subscription = subscription;
      this.handler = handler;
      return handle;
    }

    void emit(String destination, Message message, int attempt) {
      handler.handle(message, new MessageContext(
          subscription.subscriberId(),
          subscription.logicalChannelFor(destination),
          attempt));
    }
  }

  private static final class TestSubscription implements MessageSubscription {
    private final AtomicBoolean running = new AtomicBoolean(true);

    @Override
    public boolean isRunning() {
      return running.get();
    }

    @Override
    public void stop() {
      running.set(false);
    }
  }
}
