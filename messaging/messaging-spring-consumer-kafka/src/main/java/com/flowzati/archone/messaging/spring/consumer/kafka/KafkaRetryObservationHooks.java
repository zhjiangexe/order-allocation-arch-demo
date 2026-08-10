package com.flowzati.archone.messaging.spring.consumer.kafka;

import java.util.Objects;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.listener.BackOffHandler;
import org.springframework.kafka.listener.DefaultBackOffHandler;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.listener.RetryListener;

/** Bridges Spring Kafka's decision callbacks to a non-disruptive failure observer. */
final class KafkaRetryObservationHooks implements RetryListener {

  private static final Log LOGGER = LogFactory.getLog(KafkaRetryObservationHooks.class);

  private final KafkaConsumerFailureObserver observer;
  private final ThreadLocal<FailedDelivery> pendingDelivery = new ThreadLocal<>();
  private final BackOffHandler delegate = new DefaultBackOffHandler();

  KafkaRetryObservationHooks(KafkaConsumerFailureObserver observer) {
    this.observer = Objects.requireNonNull(observer, "Kafka consumer failure observer is required");
  }

  BackOffHandler observingBackOffHandler() {
    return new BackOffHandler() {
      @Override
      public void onNextBackOff(
          MessageListenerContainer container,
          Exception exception,
          long nextBackOff
      ) {
        FailedDelivery pending = pendingDelivery.get();
        pendingDelivery.remove();
        if (pending != null) {
          safelyObserve(() -> observer.retryScheduled(
              pending.record(), pending.failure(), pending.deliveryAttempt(), nextBackOff));
        }
        delegate.onNextBackOff(container, exception, nextBackOff);
      }
    };
  }

  @Override
  public void failedDelivery(
      ConsumerRecord<?, ?> record,
      Exception exception,
      int deliveryAttempt
  ) {
    pendingDelivery.set(new FailedDelivery(record, exception, deliveryAttempt));
  }

  @Override
  public void recovered(ConsumerRecord<?, ?> record, Exception exception) {
    pendingDelivery.remove();
    safelyObserve(() -> observer.deadLetterPublished(record, exception));
  }

  @Override
  public void recoveryFailed(
      ConsumerRecord<?, ?> record,
      Exception original,
      Exception failure
  ) {
    pendingDelivery.remove();
    safelyObserve(() -> observer.deadLetterPublicationFailed(record, original, failure));
  }

  private void safelyObserve(Runnable notification) {
    try {
      notification.run();
    } catch (RuntimeException observationFailure) {
      // Instrumentation must never alter retry, offset, or DLT behavior.
      LOGGER.warn("Kafka failure observation callback failed", observationFailure);
    }
  }

  private record FailedDelivery(
      ConsumerRecord<?, ?> record,
      Exception failure,
      int deliveryAttempt
  ) {
  }
}
