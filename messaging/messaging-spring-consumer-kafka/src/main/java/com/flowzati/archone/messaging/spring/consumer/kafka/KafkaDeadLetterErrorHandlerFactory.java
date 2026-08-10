package com.flowzati.archone.messaging.spring.consumer.kafka;

import com.flowzati.archone.messaging.consumer.common.MessageFailureClassifier;
import com.flowzati.archone.messaging.consumer.common.ResolvedMessageSubscription;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.FixedBackOff;

/** Builds a Spring Kafka retry/DLT handler from a transport-neutral failure classifier. */
public final class KafkaDeadLetterErrorHandlerFactory {

  private static final BackOff NO_RETRY = new FixedBackOff(0, 0);

  private KafkaDeadLetterErrorHandlerFactory() {
  }

  /**
   * Binds DLT metadata and failure observations to each real programmatic subscription.
   *
   * <p>This avoids the ambiguous global pattern that attempts to derive a subscriber from a
   * physical topic. Two subscribers may therefore consume the same topic with different groups
   * and policies without sharing recovery metadata.
   */
  public static KafkaSubscriptionErrorHandlerFactory perSubscription(
      KafkaOperations<Object, Object> kafkaOperations,
      KafkaConsumerFailurePolicyResolver failurePolicyResolver,
      Function<ResolvedMessageSubscription, KafkaConsumerFailureObserver> observerFactory
  ) {
    Objects.requireNonNull(kafkaOperations, "Kafka operations are required");
    Objects.requireNonNull(failurePolicyResolver, "Kafka failure policy resolver is required");
    Objects.requireNonNull(observerFactory, "Kafka failure observer factory is required");
    return subscription -> {
      Objects.requireNonNull(subscription, "Resolved message subscription is required");
      KafkaConsumerFailurePolicy policy = Objects.requireNonNull(
          failurePolicyResolver.resolve(subscription),
          "Kafka failure policy resolver returned null");
      KafkaConsumerFailureObserver observer = Objects.requireNonNull(
          observerFactory.apply(subscription),
          "Kafka failure observer factory returned null");
      return Optional.of(create(
          kafkaOperations,
          policy.retryBackOff(),
          policy.failureClassifier(),
          KafkaDeadLetterHeadersProvider.forSubscription(subscription),
          observer));
    };
  }

  public static DefaultErrorHandler create(
      KafkaOperations<Object, Object> kafkaOperations,
      BackOff retryBackOff,
      MessageFailureClassifier failureClassifier,
      KafkaDeadLetterHeadersProvider headersProvider
  ) {
    return create(
        kafkaOperations,
        retryBackOff,
        failureClassifier,
        headersProvider,
        KafkaConsumerFailureObserver.none());
  }

  public static DefaultErrorHandler create(
      KafkaOperations<Object, Object> kafkaOperations,
      BackOff retryBackOff,
      MessageFailureClassifier failureClassifier,
      KafkaDeadLetterHeadersProvider headersProvider,
      KafkaConsumerFailureObserver failureObserver
  ) {
    Objects.requireNonNull(kafkaOperations, "Kafka operations are required");
    Objects.requireNonNull(retryBackOff, "Kafka retry backoff is required");
    Objects.requireNonNull(failureClassifier, "Message failure classifier is required");
    Objects.requireNonNull(headersProvider, "Kafka dead-letter headers provider is required");
    Objects.requireNonNull(failureObserver, "Kafka consumer failure observer is required");

    DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaOperations);
    recoverer.setAppendOriginalHeaders(true);
    recoverer.setFailIfSendResultIsError(true);
    recoverer.setHeadersFunction((record, failure) -> Objects.requireNonNull(
        headersProvider.headersFor(record, failure),
        "Kafka dead-letter headers provider returned null"));

    KafkaRetryObservationHooks observationHooks = new KafkaRetryObservationHooks(
        failureObserver, failureClassifier);
    DefaultErrorHandler errorHandler = new DefaultErrorHandler(
        recoverer, retryBackOff, observationHooks.observingBackOffHandler());
    errorHandler.setRetryListeners(observationHooks);
    // The transport-neutral classifier owns the retry decision, including wrapped failures.
    errorHandler.setClassifications(Map.of(), true);
    errorHandler.setBackOffFunction((record, failure) ->
        failureClassifier.classify(failure).retryable() ? retryBackOff : NO_RETRY);
    return errorHandler;
  }
}
