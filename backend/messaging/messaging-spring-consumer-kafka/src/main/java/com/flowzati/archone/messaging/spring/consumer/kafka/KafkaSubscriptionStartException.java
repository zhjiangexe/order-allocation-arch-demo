package com.flowzati.archone.messaging.spring.consumer.kafka;

/** Indicates that a programmatic Kafka subscription could not start. */
public final class KafkaSubscriptionStartException extends IllegalStateException {

    public KafkaSubscriptionStartException(String containerId, Throwable cause) {
        super("Failed to start Kafka subscription: " + containerId, cause);
    }
}
