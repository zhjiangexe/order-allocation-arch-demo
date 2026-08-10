package com.flowzati.archone.messaging.spring.consumer.kafka;

import com.flowzati.archone.messaging.kafka.KafkaMessageMapper;
import com.flowzati.archone.messaging.observation.MessagingObservationNames;
import com.flowzati.archone.messaging.observation.MessagingObservationOutcome;
import com.flowzati.archone.messaging.observation.MessagingObservationTags;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;

/** Micrometer implementation of exact retry-scheduled and DLT publication callbacks. */
public final class MicrometerKafkaConsumerFailureObserver
    implements KafkaConsumerFailureObserver {

  private final ObservationRegistry observationRegistry;
  private final KafkaConsumerObservationMetadataResolver metadataResolver;

  public MicrometerKafkaConsumerFailureObserver(
      ObservationRegistry observationRegistry,
      KafkaConsumerObservationMetadataResolver metadataResolver
  ) {
    this.observationRegistry = Objects.requireNonNull(
        observationRegistry, "Observation registry is required");
    this.metadataResolver = Objects.requireNonNull(
        metadataResolver, "Kafka observation metadata resolver is required");
  }

  @Override
  public void retryScheduled(
      ConsumerRecord<?, ?> record,
      Exception failure,
      int deliveryAttempt,
      long nextBackOffMillis
  ) {
    Observation observation = observation(
        MessagingObservationNames.CONSUMER_RETRY,
        "retry",
        record,
        failure,
        MessagingObservationOutcome.RETRY_SCHEDULED)
        .highCardinalityKeyValue(
            MessagingObservationTags.RETRY_ATTEMPT, Integer.toString(deliveryAttempt))
        .highCardinalityKeyValue(
            MessagingObservationTags.RETRY_BACKOFF_MILLIS,
            Long.toString(nextBackOffMillis));
    observation.start().stop();
  }

  @Override
  public void deadLetterPublished(ConsumerRecord<?, ?> record, Exception originalFailure) {
    observation(
        MessagingObservationNames.CONSUMER_DLT,
        "dlt",
        record,
        originalFailure,
        MessagingObservationOutcome.PUBLISHED)
        .start()
        .stop();
  }

  @Override
  public void deadLetterPublicationFailed(
      ConsumerRecord<?, ?> record,
      Exception originalFailure,
      Exception publicationFailure
  ) {
    Observation observation = observation(
        MessagingObservationNames.CONSUMER_DLT,
        "dlt",
        record,
        publicationFailure,
        MessagingObservationOutcome.FAILED);
    observation.start();
    observation.error(publicationFailure);
    observation.stop();
  }

  private Observation observation(
      String name,
      String operation,
      ConsumerRecord<?, ?> record,
      Exception failure,
      MessagingObservationOutcome outcome
  ) {
    Objects.requireNonNull(record, "Kafka consumer record is required");
    KafkaConsumerObservationMetadata metadata = metadataResolver.resolve(record);
    Observation observation = Observation.createNotStarted(name, observationRegistry)
        .contextualName(metadata.logicalChannel() + " " + operation)
        .lowCardinalityKeyValue(
            MessagingObservationTags.SUBSCRIBER_ID, metadata.subscriberId())
        .lowCardinalityKeyValue(
            MessagingObservationTags.LOGICAL_DESTINATION, metadata.logicalChannel())
        .lowCardinalityKeyValue(
            MessagingObservationTags.MESSAGE_TYPE,
            textHeader(record, KafkaMessageMapper.LEGACY_EVENT_TYPE_HEADER)
                .orElse(MessagingObservationTags.UNKNOWN))
        .lowCardinalityKeyValue(MessagingObservationTags.OUTCOME, outcome.tagValue())
        .lowCardinalityKeyValue(
            MessagingObservationTags.EXCEPTION_TYPE, exceptionType(failure))
        .highCardinalityKeyValue(
            MessagingObservationTags.KAFKA_PARTITION, Integer.toString(record.partition()))
        .highCardinalityKeyValue(
            MessagingObservationTags.KAFKA_OFFSET, Long.toString(record.offset()));
    if (record.key() != null) {
      observation.highCardinalityKeyValue(
          MessagingObservationTags.PARTITION_ID, record.key().toString());
    }
    textHeader(record, KafkaMessageMapper.LEGACY_ID_HEADER).ifPresent(messageId ->
        observation.highCardinalityKeyValue(MessagingObservationTags.MESSAGE_ID, messageId));
    return observation;
  }

  private Optional<String> textHeader(ConsumerRecord<?, ?> record, String name) {
    Header header = record.headers().lastHeader(name);
    if (header == null || header.value() == null || header.value().length == 0) {
      return Optional.empty();
    }
    return Optional.of(new String(header.value(), StandardCharsets.UTF_8));
  }

  private String exceptionType(Exception failure) {
    return failure == null
        ? MessagingObservationTags.NO_EXCEPTION
        : failure.getClass().getName();
  }
}
