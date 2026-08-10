package com.flowzati.archone.messaging.spring.consumer.kafka;

import com.flowzati.archone.messaging.consumer.common.MessageFailureClassification;
import java.util.Objects;
import org.apache.kafka.clients.consumer.ConsumerRecord;

/** Immutable classified processing failure passed to Kafka retry and DLT observers. */
public record KafkaConsumerFailureContext(
    ConsumerRecord<?, ?> record,
    Exception failure,
    MessageFailureClassification classification,
    int deliveryAttempt
) {

  public KafkaConsumerFailureContext {
    Objects.requireNonNull(record, "Kafka consumer record is required");
    Objects.requireNonNull(failure, "Kafka consumer failure is required");
    Objects.requireNonNull(classification, "Kafka failure classification is required");
    if (deliveryAttempt < 1) {
      throw new IllegalArgumentException("Kafka delivery attempt must be positive");
    }
  }
}
