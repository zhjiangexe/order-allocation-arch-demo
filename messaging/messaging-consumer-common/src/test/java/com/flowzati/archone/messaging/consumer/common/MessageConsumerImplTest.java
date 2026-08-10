package com.flowzati.archone.messaging.consumer.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowzati.archone.messaging.api.MapBasedChannelMapping;
import com.flowzati.archone.messaging.api.MapBasedConsumerGroupMapping;
import com.flowzati.archone.messaging.api.Message;
import com.flowzati.archone.messaging.api.MessageBuilder;
import com.flowzati.archone.messaging.api.MessageContext;
import com.flowzati.archone.messaging.api.MessageHandler;
import com.flowzati.archone.messaging.api.MessageSubscription;
import com.flowzati.archone.messaging.api.MessageSubscriptionOptions;
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
        "allocation-inbox-scope",
        Set.of("order-events"),
        (message, context) -> handledContext.set(context),
        MessageSubscriptionOptions.withConsumerGroupId("allocation-kafka-group"));

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
  void defaultsTheConsumerGroupToTheSubscriberInTheTramShapedOverload() {
    CapturingImplementation implementation = new CapturingImplementation();
    MessageConsumerImpl consumer = new MessageConsumerImpl(implementation);

    consumer.subscribe(
        "allocation",
        Set.of("order-events"),
        (message, context) -> { });

    assertThat(implementation.subscription.subscriberId()).isEqualTo("allocation");
    assertThat(implementation.subscription.consumerGroupId()).isEqualTo("allocation");
  }

  @Test
  void appliesConfiguredGroupMappingUnlessTheSubscriptionOverridesIt() {
    CapturingImplementation implementation = new CapturingImplementation();
    MessageConsumerImpl consumer = new MessageConsumerImpl(
        implementation,
        logicalChannel -> logicalChannel,
        new MapBasedConsumerGroupMapping(Map.of("allocation", "allocation-v2")),
        List.of());

    consumer.subscribe("allocation", Set.of("order-events"), (message, context) -> { });
    assertThat(implementation.subscription.consumerGroupId()).isEqualTo("allocation-v2");

    consumer.subscribe(
        "allocation",
        Set.of("order-events"),
        (message, context) -> { },
        MessageSubscriptionOptions.withConsumerGroupId("replay-group"));
    assertThat(implementation.subscription.consumerGroupId()).isEqualTo("replay-group");
  }

  @Test
  void appliesTheDecoratorChainBeforeTheApplicationHandler() {
    CapturingImplementation implementation = new CapturingImplementation();
    List<String> calls = new ArrayList<>();
    MessageConsumerImpl consumer = new MessageConsumerImpl(
        implementation,
        logicalChannel -> logicalChannel,
        List.of(decorator(200, "inner", calls), decorator(100, "outer", calls)));

    subscribe(consumer, (message, context) -> calls.add("handler"));
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
    assertThatThrownBy(() -> consumer.subscribe(
        "subscriber", Set.of("orders", "stock"), (message, context) -> { }))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Multiple logical channels map to the same destination: shared-topic");
    assertThat(implementation.subscription).isNull();
  }

  @Test
  void rejectsAContextThatDoesNotBelongToTheLocalSubscription() {
    CapturingImplementation implementation = new CapturingImplementation();
    MessageConsumerImpl consumer = new MessageConsumerImpl(implementation);
    subscribe(consumer, (message, context) -> { });

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

  private void subscribe(MessageConsumerImpl consumer, MessageHandler handler) {
    consumer.subscribe(
        "allocation-inbox-scope",
        Set.of("order-events"),
        handler,
        MessageSubscriptionOptions.withConsumerGroupId("allocation-kafka-group"));
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
