package com.flowzati.archone.messaging.spring.consumer.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;

/** Optional hook invoked only after retry or DLT decisions have been made by Spring Kafka. */
public interface KafkaConsumerFailureObserver {

  void retryScheduled(
      ConsumerRecord<?, ?> record,
      Exception failure,
      int deliveryAttempt,
      long nextBackOffMillis
  );

  void deadLetterPublished(ConsumerRecord<?, ?> record, Exception originalFailure);

  void deadLetterPublicationFailed(
      ConsumerRecord<?, ?> record,
      Exception originalFailure,
      Exception publicationFailure
  );

  static KafkaConsumerFailureObserver none() {
    return NoOpKafkaConsumerFailureObserver.INSTANCE;
  }

  enum NoOpKafkaConsumerFailureObserver implements KafkaConsumerFailureObserver {
    INSTANCE;

    @Override
    public void retryScheduled(
        ConsumerRecord<?, ?> record,
        Exception failure,
        int deliveryAttempt,
        long nextBackOffMillis
    ) {
    }

    @Override
    public void deadLetterPublished(ConsumerRecord<?, ?> record, Exception originalFailure) {
    }

    @Override
    public void deadLetterPublicationFailed(
        ConsumerRecord<?, ?> record,
        Exception originalFailure,
        Exception publicationFailure
    ) {
    }
  }
}
