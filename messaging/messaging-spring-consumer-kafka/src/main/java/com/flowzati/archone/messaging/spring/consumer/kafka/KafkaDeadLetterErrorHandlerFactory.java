package com.flowzati.archone.messaging.spring.consumer.kafka;

import com.flowzati.archone.messaging.consumer.common.MessageFailureClassifier;
import java.util.Map;
import java.util.Objects;
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

  public static DefaultErrorHandler create(
      KafkaOperations<Object, Object> kafkaOperations,
      BackOff retryBackOff,
      MessageFailureClassifier failureClassifier
  ) {
    Objects.requireNonNull(kafkaOperations, "Kafka operations are required");
    Objects.requireNonNull(retryBackOff, "Kafka retry backoff is required");
    Objects.requireNonNull(failureClassifier, "Message failure classifier is required");

    DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaOperations);
    recoverer.setAppendOriginalHeaders(true);
    recoverer.setFailIfSendResultIsError(true);

    DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, retryBackOff);
    // The transport-neutral classifier owns the retry decision, including wrapped failures.
    errorHandler.setClassifications(Map.of(), true);
    errorHandler.setBackOffFunction((record, failure) ->
        failureClassifier.classify(failure).retryable() ? retryBackOff : NO_RETRY);
    return errorHandler;
  }
}
