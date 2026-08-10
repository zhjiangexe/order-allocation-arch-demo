package com.flowzati.archone.messaging.spring.consumer.kafka;

import com.flowzati.archone.messaging.api.MessageContext;
import com.flowzati.archone.messaging.api.MessageHandler;
import com.flowzati.archone.messaging.api.MessageSubscription;
import com.flowzati.archone.messaging.consumer.common.MessageConsumerImplementation;
import com.flowzati.archone.messaging.consumer.common.ResolvedMessageSubscription;
import com.flowzati.archone.messaging.kafka.KafkaMessageMapper;
import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.kafka.support.KafkaHeaders;

/** Generic consumer SPI implementation backed by programmatically created Spring Kafka containers. */
public final class SpringKafkaMessageConsumerImplementation
    implements MessageConsumerImplementation, AutoCloseable {

  private static final System.Logger LOGGER = System.getLogger(
      SpringKafkaMessageConsumerImplementation.class.getName());

  private final ConcurrentKafkaListenerContainerFactory<String, String> containerFactory;
  private final KafkaMessageMapper messageMapper;
  private final KafkaSubscriptionPolicyResolver policyResolver;
  private final KafkaSubscriptionErrorHandlerFactory errorHandlerFactory;
  private final KafkaSubscriptionRegistry registry;
  private final KafkaContainerStarter containerStarter;
  private final Map<String, DefaultKafkaMessageSubscription> activeSubscriptions =
      new LinkedHashMap<>();
  private boolean closed;

  public SpringKafkaMessageConsumerImplementation(
      ConcurrentKafkaListenerContainerFactory<String, String> containerFactory,
      KafkaMessageMapper messageMapper,
      KafkaSubscriptionPolicyResolver policyResolver
  ) {
    this(
        containerFactory,
        messageMapper,
        policyResolver,
        KafkaSubscriptionErrorHandlerFactory.none());
  }

  public SpringKafkaMessageConsumerImplementation(
      ConcurrentKafkaListenerContainerFactory<String, String> containerFactory,
      KafkaMessageMapper messageMapper,
      KafkaSubscriptionPolicyResolver policyResolver,
      KafkaSubscriptionErrorHandlerFactory errorHandlerFactory
  ) {
    this(containerFactory, messageMapper, policyResolver, errorHandlerFactory,
        new KafkaSubscriptionRegistry(), ConcurrentMessageListenerContainer::start);
  }

  SpringKafkaMessageConsumerImplementation(
      ConcurrentKafkaListenerContainerFactory<String, String> containerFactory,
      KafkaMessageMapper messageMapper,
      KafkaSubscriptionPolicyResolver policyResolver,
      KafkaSubscriptionErrorHandlerFactory errorHandlerFactory,
      KafkaSubscriptionRegistry registry,
      KafkaContainerStarter containerStarter
  ) {
    this.containerFactory = Objects.requireNonNull(
        containerFactory, "Kafka listener container factory is required");
    this.messageMapper = Objects.requireNonNull(messageMapper, "Kafka message mapper is required");
    this.policyResolver = Objects.requireNonNull(
        policyResolver, "Kafka subscription policy resolver is required");
    this.errorHandlerFactory = Objects.requireNonNull(
        errorHandlerFactory, "Kafka subscription error handler factory is required");
    this.registry = Objects.requireNonNull(registry, "Kafka subscription registry is required");
    this.containerStarter = Objects.requireNonNull(
        containerStarter, "Kafka container starter is required");
  }

  @Override
  public synchronized MessageSubscription subscribe(
      ResolvedMessageSubscription subscription,
      MessageHandler handler
  ) {
    Objects.requireNonNull(subscription, "Resolved message subscription is required");
    Objects.requireNonNull(handler, "Message handler is required");
    if (closed) {
      throw new IllegalStateException("Kafka message consumer is closed");
    }
    KafkaSubscriptionPolicy policy = Objects.requireNonNull(
        policyResolver.resolve(subscription), "Kafka subscription policy resolver returned null");
    String containerId = KafkaContainerIdentity.from(
        subscription.subscriberId(), subscription.consumerGroupId());
    ConcurrentMessageListenerContainer<String, String> container = containerFactory.createContainer(
        subscription.destinationToLogicalChannel().keySet().toArray(String[]::new));
    boolean autoStartup = configure(container, subscription, handler, policy, containerId);

    KafkaSubscriptionRegistration registration = new KafkaSubscriptionRegistration(
        subscription.subscriberId(),
        subscription.consumerGroupId(),
        subscription.destinationToLogicalChannel().keySet(),
        containerId);
    registry.reserve(registration);
    DefaultKafkaMessageSubscription handle = new DefaultKafkaMessageSubscription(
        registration,
        container,
        () -> release(registration));
    activeSubscriptions.put(subscription.subscriberId(), handle);
    try {
      if (autoStartup) {
        containerStarter.start(container);
      }
      return handle;
    } catch (RuntimeException failure) {
      cleanupAfterStartFailure(handle, failure);
      throw new KafkaSubscriptionStartException(containerId, failure);
    } catch (Error failure) {
      cleanupAfterStartFailure(handle, failure);
      throw failure;
    }
  }

  @Override
  public void close() {
    Map<String, DefaultKafkaMessageSubscription> snapshot;
    synchronized (this) {
      if (closed) {
        return;
      }
      closed = true;
      snapshot = Map.copyOf(activeSubscriptions);
    }
    RuntimeException firstFailure = null;
    for (DefaultKafkaMessageSubscription subscription : snapshot.values()) {
      try {
        subscription.stop();
      } catch (RuntimeException failure) {
        if (firstFailure == null) {
          firstFailure = failure;
        } else {
          firstFailure.addSuppressed(failure);
        }
      }
    }
    if (firstFailure != null) {
      throw firstFailure;
    }
  }

  private boolean configure(
      ConcurrentMessageListenerContainer<String, String> container,
      ResolvedMessageSubscription subscription,
      MessageHandler handler,
      KafkaSubscriptionPolicy policy,
      String containerId
  ) {
    container.setBeanName(containerId);
    container.setMainListenerId(containerId);
    boolean autoStartup = policy.autoStartupOverride().orElse(container.isAutoStartup());
    // The runtime owns lifecycle explicitly; the factory value only decides whether to start now.
    container.setAutoStartup(false);
    policy.concurrencyOverride().ifPresent(container::setConcurrency);
    container.getContainerProperties().setGroupId(subscription.consumerGroupId());
    policy.ackModeOverride().ifPresent(container.getContainerProperties()::setAckMode);
    policy.missingTopicsFatalOverride()
        .ifPresent(container.getContainerProperties()::setMissingTopicsFatal);
    policy.shutdownTimeoutOverride()
        .ifPresent(timeout -> container.getContainerProperties()
            .setShutdownTimeout(timeout.toMillis()));
    policy.observationEnabledOverride()
        .ifPresent(container.getContainerProperties()::setObservationEnabled);
    if (container.getContainerProperties().isObservationEnabled()) {
      // Spring Kafka observation supersedes its legacy listener timers; keep one transport meter.
      container.getContainerProperties().setMicrometerEnabled(false);
    }
    policy.commonErrorHandler()
        .or(() -> errorHandlerFactory.create(subscription))
        .ifPresent(container::setCommonErrorHandler);
    CommonErrorHandler errorHandler = container.getCommonErrorHandler();
    if (errorHandler != null && errorHandler.deliveryAttemptHeader()) {
      container.getContainerProperties().setDeliveryAttemptHeader(true);
    }
    container.getContainerProperties().setMessageListener(
        (MessageListener<String, String>) record -> dispatch(record, subscription, handler));
    LOGGER.log(System.Logger.Level.INFO, () ->
        "Kafka subscription configured [containerId=" + containerId
            + ", subscriberId=" + subscription.subscriberId()
            + ", consumerGroupId=" + subscription.consumerGroupId()
            + ", destinations=" + subscription.destinationToLogicalChannel().keySet()
            + ", concurrency=" + container.getConcurrency()
            + ", ackMode=" + container.getContainerProperties().getAckMode()
            + ", missingTopicsFatal="
            + container.getContainerProperties().isMissingTopicsFatal()
            + ", observationEnabled="
            + container.getContainerProperties().isObservationEnabled()
            + ", autoStartup=" + autoStartup
            + ", shutdownTimeoutMs="
            + container.getContainerProperties().getShutdownTimeout() + "]");
    return autoStartup;
  }

  private void dispatch(
      ConsumerRecord<String, String> record,
      ResolvedMessageSubscription subscription,
      MessageHandler handler
  ) {
    handler.handle(
        messageMapper.map(record),
        new MessageContext(
            subscription.subscriberId(),
            subscription.logicalChannelFor(record.topic()),
            deliveryAttempt(record)));
  }

  private int deliveryAttempt(ConsumerRecord<String, String> record) {
    Header header = record.headers().lastHeader(KafkaHeaders.DELIVERY_ATTEMPT);
    if (header == null) {
      return 1;
    }
    if (header.value() == null || header.value().length != Integer.BYTES) {
      throw new IllegalArgumentException("Invalid Kafka delivery attempt header");
    }
    int attempt = ByteBuffer.wrap(header.value()).getInt();
    if (attempt < 1) {
      throw new IllegalArgumentException("Invalid Kafka delivery attempt header");
    }
    return attempt;
  }

  private synchronized void release(KafkaSubscriptionRegistration registration) {
    registry.release(registration);
    activeSubscriptions.remove(registration.subscriberId());
  }

  private void cleanupAfterStartFailure(
      DefaultKafkaMessageSubscription handle,
      Throwable startFailure
  ) {
    try {
      handle.stop();
    } catch (RuntimeException cleanupFailure) {
      startFailure.addSuppressed(cleanupFailure);
    }
  }

  @FunctionalInterface
  interface KafkaContainerStarter {

    void start(ConcurrentMessageListenerContainer<String, String> container);
  }
}
