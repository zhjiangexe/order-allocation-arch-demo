package com.flowzati.archone.messaging.spring.consumer.kafka;

import com.flowzati.archone.messaging.consumer.common.MessageFailureClassifier;
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
    private final MessageFailureClassifier failureClassifier;
    private final ThreadLocal<KafkaConsumerFailureContext> pendingFailure = new ThreadLocal<>();
    private final BackOffHandler delegate = new DefaultBackOffHandler();

    KafkaRetryObservationHooks(KafkaConsumerFailureObserver observer, MessageFailureClassifier failureClassifier) {
        this.observer = Objects.requireNonNull(observer, "Kafka consumer failure observer is required");
        this.failureClassifier = Objects.requireNonNull(failureClassifier, "Message failure classifier is required");
    }

    BackOffHandler observingBackOffHandler() {
        return new BackOffHandler() {
            @Override
            public void onNextBackOff(MessageListenerContainer container, Exception exception, long nextBackOff) {
                KafkaConsumerFailureContext pending = pendingFailure.get();
                pendingFailure.remove();
                if (pending != null) {
                    safelyObserve(() -> observer.retryScheduled(pending, nextBackOff));
                }
                delegate.onNextBackOff(container, exception, nextBackOff);
            }
        };
    }

    @Override
    public void failedDelivery(ConsumerRecord<?, ?> record, Exception exception, int deliveryAttempt) {
        pendingFailure.set(context(record, exception, deliveryAttempt));
    }

    @Override
    public void recovered(ConsumerRecord<?, ?> record, Exception exception) {
        KafkaConsumerFailureContext context = takeOrCreateContext(record, exception);
        safelyObserve(() -> observer.deadLetterPublished(context));
    }

    @Override
    public void recoveryFailed(ConsumerRecord<?, ?> record, Exception original, Exception failure) {
        KafkaConsumerFailureContext context = takeOrCreateContext(record, original);
        safelyObserve(() -> observer.deadLetterPublicationFailed(context, failure));
    }

    private KafkaConsumerFailureContext takeOrCreateContext(ConsumerRecord<?, ?> record, Exception failure) {
        KafkaConsumerFailureContext pending = pendingFailure.get();
        pendingFailure.remove();
        return pending == null ? context(record, failure, 1) : pending;
    }

    private KafkaConsumerFailureContext context(ConsumerRecord<?, ?> record, Exception failure, int deliveryAttempt) {
        return new KafkaConsumerFailureContext(record, failure, failureClassifier.classify(failure), deliveryAttempt);
    }

    private void safelyObserve(Runnable notification) {
        try {
            notification.run();
        } catch (RuntimeException observationFailure) {
            // Instrumentation must never alter retry, offset, or DLT behavior.
            LOGGER.warn("Kafka failure observation callback failed", observationFailure);
        }
    }
}
