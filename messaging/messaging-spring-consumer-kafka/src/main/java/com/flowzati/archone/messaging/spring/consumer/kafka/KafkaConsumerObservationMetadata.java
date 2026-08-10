package com.flowzati.archone.messaging.spring.consumer.kafka;

/** Low-cardinality subscriber identity resolved independently from a raw Kafka record. */
public record KafkaConsumerObservationMetadata(String subscriberId, String logicalChannel) {

  public KafkaConsumerObservationMetadata {
    if (isBlank(subscriberId) || isBlank(logicalChannel)) {
      throw new IllegalArgumentException("Kafka consumer observation metadata is required");
    }
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
