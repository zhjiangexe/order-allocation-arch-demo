package com.flowzati.archone.messaging.spring.consumer.kafka;

import com.flowzati.archone.messaging.consumer.common.MessageFailureClassifier;
import java.util.Objects;
import org.springframework.util.backoff.BackOff;

/** Application-owned retry classification and backoff for one Kafka subscription. */
public record KafkaConsumerFailurePolicy(BackOff retryBackOff, MessageFailureClassifier failureClassifier) {

    public KafkaConsumerFailurePolicy {
        Objects.requireNonNull(retryBackOff, "Kafka retry backoff is required");
        Objects.requireNonNull(failureClassifier, "Message failure classifier is required");
    }
}
