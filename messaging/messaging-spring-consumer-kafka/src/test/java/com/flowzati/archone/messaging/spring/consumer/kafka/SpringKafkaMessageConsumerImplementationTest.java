package com.flowzati.archone.messaging.spring.consumer.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.flowzati.archone.messaging.api.Message;
import com.flowzati.archone.messaging.api.MessageContext;
import com.flowzati.archone.messaging.consumer.common.ResolvedMessageSubscription;
import com.flowzati.archone.messaging.kafka.KafkaMessageMapper;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.kafka.mock.MockConsumerFactory;
import org.springframework.kafka.support.KafkaHeaders;

class SpringKafkaMessageConsumerImplementationTest {

  private static final String TOPIC = "ordering.order-events";

  @Test
  void appliesSubscriberPolicyAndDispatchesMappedMessageWithLogicalContext() {
    CommonErrorHandler errorHandler = mock(CommonErrorHandler.class);
    KafkaSubscriptionPolicy policy = KafkaSubscriptionPolicy.builder()
        .concurrency(3)
        .ackMode(ContainerProperties.AckMode.RECORD)
        .missingTopicsFatal(false)
        .observationEnabled(true)
        .shutdownTimeout(Duration.ofSeconds(7))
        .commonErrorHandler(errorHandler)
        .build();
    SpringKafkaMessageConsumerImplementation consumer = consumer(
        KafkaSubscriptionPolicyResolver.fixed(policy), container -> { });
    AtomicReference<Message> receivedMessage = new AtomicReference<>();
    AtomicReference<MessageContext> receivedContext = new AtomicReference<>();

    KafkaMessageSubscription handle = (KafkaMessageSubscription) consumer.subscribe(
        subscription("subscriber-a", "group-a", TOPIC),
        (message, context) -> {
          receivedMessage.set(message);
          receivedContext.set(context);
        });
    DefaultKafkaMessageSubscription implementation = (DefaultKafkaMessageSubscription) handle;
    ConcurrentMessageListenerContainer<String, String> container = implementation.container();

    assertThat(container.getContainerProperties().getTopics()).containsExactly(TOPIC);
    assertThat(container.getGroupId()).isEqualTo("group-a");
    assertThat(container.getConcurrency()).isEqualTo(3);
    assertThat(container.getContainerProperties().getAckMode())
        .isEqualTo(ContainerProperties.AckMode.RECORD);
    assertThat(container.getContainerProperties().isMissingTopicsFatal()).isFalse();
    assertThat(container.getContainerProperties().isObservationEnabled()).isTrue();
    assertThat(container.getContainerProperties().isMicrometerEnabled()).isFalse();
    assertThat(container.getContainerProperties().getShutdownTimeout()).isEqualTo(7_000);
    assertThat(container.getCommonErrorHandler()).isSameAs(errorHandler);
    assertThat(handle.containerId()).startsWith("archone-messaging.");

    @SuppressWarnings("unchecked")
    MessageListener<String, String> listener = (MessageListener<String, String>)
        container.getContainerProperties().getMessageListener();
    listener.onMessage(record(4));

    assertThat(receivedMessage.get().type()).isEqualTo("OrderPlaced.v1");
    assertThat(receivedContext.get()).isEqualTo(
        new MessageContext("subscriber-a", "ordering-events", 4));
    handle.stop();
  }

  @Test
  void inheritsSharedFactorySettingsWhenSubscriberHasNoOverrides() {
    ConcurrentKafkaListenerContainerFactory<String, String> factory = containerFactory();
    factory.setConcurrency(4);
    factory.setAutoStartup(false);
    factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
    factory.getContainerProperties().setMissingTopicsFatal(false);
    factory.getContainerProperties().setObservationEnabled(true);
    factory.getContainerProperties().setShutdownTimeout(9_000);
    AtomicInteger starts = new AtomicInteger();
    SpringKafkaMessageConsumerImplementation consumer = consumer(
        factory,
        KafkaSubscriptionPolicyResolver.fixed(KafkaSubscriptionPolicy.defaults()),
        KafkaSubscriptionErrorHandlerFactory.none(),
        container -> starts.incrementAndGet());

    DefaultKafkaMessageSubscription handle = (DefaultKafkaMessageSubscription) consumer.subscribe(
        subscription("subscriber-a", "group-a", TOPIC),
        (message, context) -> { });
    ConcurrentMessageListenerContainer<String, String> container = handle.container();

    assertThat(starts).hasValue(0);
    assertThat(container.getConcurrency()).isEqualTo(4);
    assertThat(container.getContainerProperties().getAckMode())
        .isEqualTo(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
    assertThat(container.getContainerProperties().isMissingTopicsFatal()).isFalse();
    assertThat(container.getContainerProperties().isObservationEnabled()).isTrue();
    assertThat(container.getContainerProperties().isMicrometerEnabled()).isFalse();
    assertThat(container.getContainerProperties().getShutdownTimeout()).isEqualTo(9_000);
    handle.stop();
  }

  @Test
  void appliesOnlyExplicitSubscriberOverridesOnTopOfTheSharedFactory() {
    ConcurrentKafkaListenerContainerFactory<String, String> factory = containerFactory();
    factory.setConcurrency(4);
    factory.setAutoStartup(false);
    factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
    factory.getContainerProperties().setMissingTopicsFatal(false);
    factory.getContainerProperties().setShutdownTimeout(9_000);
    AtomicInteger starts = new AtomicInteger();
    KafkaSubscriptionPolicy policy = KafkaSubscriptionPolicy.builder()
        .concurrency(2)
        .autoStartup(true)
        .build();
    SpringKafkaMessageConsumerImplementation consumer = consumer(
        factory,
        KafkaSubscriptionPolicyResolver.fixed(policy),
        KafkaSubscriptionErrorHandlerFactory.none(),
        container -> starts.incrementAndGet());

    DefaultKafkaMessageSubscription handle = (DefaultKafkaMessageSubscription) consumer.subscribe(
        subscription("subscriber-a", "group-a", TOPIC),
        (message, context) -> { });
    ConcurrentMessageListenerContainer<String, String> container = handle.container();

    assertThat(starts).hasValue(1);
    assertThat(container.getConcurrency()).isEqualTo(2);
    assertThat(container.getContainerProperties().getAckMode())
        .isEqualTo(ContainerProperties.AckMode.RECORD);
    assertThat(container.getContainerProperties().isMissingTopicsFatal()).isFalse();
    assertThat(container.getContainerProperties().getShutdownTimeout()).isEqualTo(9_000);
    handle.stop();
  }

  @Test
  void startsReportsReadinessAndStopsTheProgrammaticContainer() {
    SpringKafkaMessageConsumerImplementation consumer =
        new SpringKafkaMessageConsumerImplementation(
            containerFactory(),
            new KafkaMessageMapper(),
            KafkaSubscriptionPolicyResolver.fixed(KafkaSubscriptionPolicy.builder()
                .missingTopicsFatal(false)
                .build()));

    KafkaMessageSubscription handle = (KafkaMessageSubscription) consumer.subscribe(
        subscription("subscriber-a", "group-a", TOPIC),
        (message, context) -> { });

    awaitState(handle::isRunning, true);
    awaitState(handle::isReady, true);
    handle.stop();
    awaitState(handle::isRunning, false);
    assertThat(handle.isReady()).isFalse();
  }

  @Test
  void registersButDoesNotStartWhenAutoStartupIsDisabled() {
    AtomicInteger starts = new AtomicInteger();
    KafkaSubscriptionPolicy policy = KafkaSubscriptionPolicy.builder()
        .autoStartup(false)
        .build();
    SpringKafkaMessageConsumerImplementation consumer = consumer(
        KafkaSubscriptionPolicyResolver.fixed(policy),
        KafkaSubscriptionErrorHandlerFactory.none(),
        container -> starts.incrementAndGet());

    KafkaMessageSubscription handle = (KafkaMessageSubscription) consumer.subscribe(
        subscription("subscriber-a", "group-a", TOPIC),
        (message, context) -> { });

    assertThat(starts).hasValue(0);
    assertThat(handle.isRunning()).isFalse();
    handle.stop();
  }

  @Test
  void createsAnErrorHandlerFromTheExactSubscriptionWhenPolicyHasNoOverride() {
    CommonErrorHandler errorHandler = mock(CommonErrorHandler.class);
    AtomicReference<ResolvedMessageSubscription> resolved = new AtomicReference<>();
    KafkaSubscriptionErrorHandlerFactory errorHandlerFactory = subscription -> {
      resolved.set(subscription);
      return Optional.of(errorHandler);
    };
    SpringKafkaMessageConsumerImplementation consumer = consumer(
        KafkaSubscriptionPolicyResolver.fixed(KafkaSubscriptionPolicy.defaults()),
        errorHandlerFactory,
        container -> { });
    ResolvedMessageSubscription subscription = subscription(
        "subscriber-a", "group-a", TOPIC);

    KafkaMessageSubscription handle = (KafkaMessageSubscription) consumer.subscribe(
        subscription,
        (message, context) -> { });
    DefaultKafkaMessageSubscription implementation = (DefaultKafkaMessageSubscription) handle;

    assertThat(resolved).hasValue(subscription);
    assertThat(implementation.container().getCommonErrorHandler()).isSameAs(errorHandler);
    handle.stop();
  }

  @Test
  void appliesIndependentPoliciesToSubscribersSharingTheSamePhysicalTopic() {
    KafkaSubscriptionPolicyResolver policyResolver = subscription ->
        KafkaSubscriptionPolicy.builder()
            .concurrency("subscriber-a".equals(subscription.subscriberId()) ? 1 : 3)
            .ackMode("subscriber-a".equals(subscription.subscriberId())
                ? ContainerProperties.AckMode.RECORD
                : ContainerProperties.AckMode.BATCH)
            .build();
    SpringKafkaMessageConsumerImplementation consumer = consumer(
        policyResolver,
        container -> { });

    DefaultKafkaMessageSubscription first = (DefaultKafkaMessageSubscription) consumer.subscribe(
        subscription("subscriber-a", "group-a", TOPIC),
        (message, context) -> { });
    DefaultKafkaMessageSubscription second = (DefaultKafkaMessageSubscription) consumer.subscribe(
        subscription("subscriber-b", "group-b", TOPIC),
        (message, context) -> { });

    assertThat(first.container().getConcurrency()).isEqualTo(1);
    assertThat(first.container().getContainerProperties().getAckMode())
        .isEqualTo(ContainerProperties.AckMode.RECORD);
    assertThat(second.container().getConcurrency()).isEqualTo(3);
    assertThat(second.container().getContainerProperties().getAckMode())
        .isEqualTo(ContainerProperties.AckMode.BATCH);
    first.stop();
    second.stop();
  }

  @Test
  void rejectsAmbiguousSubscriberAndGroupDestinationCollisions() {
    SpringKafkaMessageConsumerImplementation consumer = consumer(
        KafkaSubscriptionPolicyResolver.fixed(KafkaSubscriptionPolicy.defaults()),
        container -> { });
    KafkaMessageSubscription first = (KafkaMessageSubscription) consumer.subscribe(
        subscription("subscriber-a", "shared-group", TOPIC),
        (message, context) -> { });

    assertThatThrownBy(() -> consumer.subscribe(
        subscription("subscriber-a", "other-group", "inventory.stock-events"),
        (message, context) -> { }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Duplicate Kafka subscriber ID: subscriber-a");
    assertThatThrownBy(() -> consumer.subscribe(
        subscription("subscriber-b", "shared-group", TOPIC),
        (message, context) -> { }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("must not share group and destinations");

    KafkaMessageSubscription independent = (KafkaMessageSubscription) consumer.subscribe(
        subscription("subscriber-c", "independent-group", TOPIC),
        (message, context) -> { });
    first.stop();
    independent.stop();
  }

  @Test
  void releasesRegistrationAfterStopAndAfterStartFailure() {
    AtomicInteger starts = new AtomicInteger();
    SpringKafkaMessageConsumerImplementation consumer = consumer(
        KafkaSubscriptionPolicyResolver.fixed(KafkaSubscriptionPolicy.defaults()),
        container -> {
          if (starts.getAndIncrement() == 0) {
            throw new IllegalStateException("start failed");
          }
        });
    ResolvedMessageSubscription subscription =
        subscription("subscriber-a", "group-a", TOPIC);

    assertThatThrownBy(() -> consumer.subscribe(subscription, (message, context) -> { }))
        .isInstanceOf(KafkaSubscriptionStartException.class)
        .hasCauseInstanceOf(IllegalStateException.class);

    KafkaMessageSubscription recovered = (KafkaMessageSubscription) consumer.subscribe(
        subscription, (message, context) -> { });
    recovered.stop();
    KafkaMessageSubscription restarted = (KafkaMessageSubscription) consumer.subscribe(
        subscription, (message, context) -> { });
    restarted.stop();
  }

  @Test
  void closeStopsEverySubscriptionAndRejectsNewOnes() {
    SpringKafkaMessageConsumerImplementation consumer = consumer(
        KafkaSubscriptionPolicyResolver.fixed(KafkaSubscriptionPolicy.defaults()),
        container -> { });
    KafkaMessageSubscription first = (KafkaMessageSubscription) consumer.subscribe(
        subscription("subscriber-a", "group-a", TOPIC),
        (message, context) -> { });
    KafkaMessageSubscription second = (KafkaMessageSubscription) consumer.subscribe(
        subscription("subscriber-b", "group-b", TOPIC),
        (message, context) -> { });

    consumer.close();
    consumer.close();

    assertThat(first.isRunning()).isFalse();
    assertThat(second.isRunning()).isFalse();
    assertThatThrownBy(() -> consumer.subscribe(
        subscription("subscriber-c", "group-c", TOPIC),
        (message, context) -> { }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Kafka message consumer is closed");
  }

  private SpringKafkaMessageConsumerImplementation consumer(
      KafkaSubscriptionPolicyResolver policyResolver,
      SpringKafkaMessageConsumerImplementation.KafkaContainerStarter starter
  ) {
    return consumer(policyResolver, KafkaSubscriptionErrorHandlerFactory.none(), starter);
  }

  private SpringKafkaMessageConsumerImplementation consumer(
      KafkaSubscriptionPolicyResolver policyResolver,
      KafkaSubscriptionErrorHandlerFactory errorHandlerFactory,
      SpringKafkaMessageConsumerImplementation.KafkaContainerStarter starter
  ) {
    return consumer(containerFactory(), policyResolver, errorHandlerFactory, starter);
  }

  private SpringKafkaMessageConsumerImplementation consumer(
      ConcurrentKafkaListenerContainerFactory<String, String> factory,
      KafkaSubscriptionPolicyResolver policyResolver,
      KafkaSubscriptionErrorHandlerFactory errorHandlerFactory,
      SpringKafkaMessageConsumerImplementation.KafkaContainerStarter starter
  ) {
    return new SpringKafkaMessageConsumerImplementation(
        factory,
        new KafkaMessageMapper(),
        policyResolver,
        errorHandlerFactory,
        new KafkaSubscriptionRegistry(),
        starter);
  }

  private ConcurrentKafkaListenerContainerFactory<String, String> containerFactory() {
    MockConsumerFactory<String, String> consumerFactory = new MockConsumerFactory<>(() -> {
      MockConsumer<String, String> consumer = new MockConsumer<>(OffsetResetStrategy.EARLIEST);
      consumer.schedulePollTask(() -> {
        TopicPartition partition = new TopicPartition(TOPIC, 0);
        consumer.updateBeginningOffsets(Map.of(partition, 0L));
        consumer.rebalance(List.of(partition));
      });
      return consumer;
    });
    ConcurrentKafkaListenerContainerFactory<String, String> factory =
        new ConcurrentKafkaListenerContainerFactory<>();
    factory.setConsumerFactory(consumerFactory);
    return factory;
  }

  private ResolvedMessageSubscription subscription(
      String subscriberId,
      String groupId,
      String topic
  ) {
    return new ResolvedMessageSubscription(
        subscriberId, groupId, Map.of(topic, logicalChannel(topic)));
  }

  private String logicalChannel(String topic) {
    return TOPIC.equals(topic) ? "ordering-events" : "inventory-events";
  }

  private ConsumerRecord<String, String> record(int deliveryAttempt) {
    ConsumerRecord<String, String> record = new ConsumerRecord<>(
        TOPIC, 0, 42, "order-1", "{\"eventId\":\"event-1\"}");
    record.headers().add(
        KafkaMessageMapper.LEGACY_ID_HEADER,
        UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
    record.headers().add(
        KafkaMessageMapper.LEGACY_EVENT_TYPE_HEADER,
        "OrderPlaced.v1".getBytes(StandardCharsets.UTF_8));
    record.headers().add(
        KafkaHeaders.DELIVERY_ATTEMPT,
        ByteBuffer.allocate(Integer.BYTES).putInt(deliveryAttempt).array());
    return record;
  }

  private void awaitState(BooleanSupplier state, boolean expected) {
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (state.getAsBoolean() != expected && System.nanoTime() < deadline) {
      LockSupport.parkNanos(Duration.ofMillis(10).toNanos());
    }
    assertThat(state.getAsBoolean()).isEqualTo(expected);
  }

  @FunctionalInterface
  private interface BooleanSupplier {

    boolean getAsBoolean();
  }
}
