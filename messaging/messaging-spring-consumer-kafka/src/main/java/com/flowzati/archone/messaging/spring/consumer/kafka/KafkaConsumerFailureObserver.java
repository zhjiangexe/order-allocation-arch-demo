package com.flowzati.archone.messaging.spring.consumer.kafka;

/** Optional hook invoked only after retry or DLT decisions have been made by Spring Kafka. */
public interface KafkaConsumerFailureObserver {

  void retryScheduled(KafkaConsumerFailureContext context, long nextBackOffMillis);

  void deadLetterPublished(KafkaConsumerFailureContext context);

  void deadLetterPublicationFailed(
      KafkaConsumerFailureContext context,
      Exception publicationFailure
  );

  static KafkaConsumerFailureObserver none() {
    return NoOpKafkaConsumerFailureObserver.INSTANCE;
  }

  enum NoOpKafkaConsumerFailureObserver implements KafkaConsumerFailureObserver {
    INSTANCE;

    @Override
    public void retryScheduled(KafkaConsumerFailureContext context, long nextBackOffMillis) {
    }

    @Override
    public void deadLetterPublished(KafkaConsumerFailureContext context) {
    }

    @Override
    public void deadLetterPublicationFailed(
        KafkaConsumerFailureContext context,
        Exception publicationFailure
    ) {
    }
  }
}
