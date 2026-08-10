package com.flowzati.archone.messaging.spring.consumer.kafka;

import com.flowzati.archone.messaging.kafka.KafkaMessageMapper;
import com.flowzati.archone.messaging.observation.MessagingObservationNames;
import com.flowzati.archone.messaging.observation.MessagingObservationOutcome;
import com.flowzati.archone.messaging.observation.MessagingObservationTags;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
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
  public void retryScheduled(KafkaConsumerFailureContext context, long nextBackOffMillis) {
    Observation observation = observation(
        MessagingObservationNames.CONSUMER_RETRY,
        "retry",
        context,
        context.failure(),
        MessagingObservationOutcome.RETRY_SCHEDULED)
        .highCardinalityKeyValue(
            MessagingObservationTags.RETRY_ATTEMPT,
            Integer.toString(context.deliveryAttempt()))
        .highCardinalityKeyValue(
            MessagingObservationTags.RETRY_BACKOFF_MILLIS,
            Long.toString(nextBackOffMillis));
    observation.start().stop();
  }

  @Override
  public void deadLetterPublished(KafkaConsumerFailureContext context) {
    observation(
        MessagingObservationNames.CONSUMER_DLT,
        "dlt",
        context,
        context.failure(),
        MessagingObservationOutcome.PUBLISHED)
        .lowCardinalityKeyValue(
            MessagingObservationTags.DLT_DISPOSITION,
            dltDisposition(context))
        .highCardinalityKeyValue(
            MessagingObservationTags.RETRY_ATTEMPT,
            Integer.toString(context.deliveryAttempt()))
        .start()
        .stop();
  }

  @Override
  public void deadLetterPublicationFailed(
      KafkaConsumerFailureContext context,
      Exception publicationFailure
  ) {
    Observation observation = observation(
        MessagingObservationNames.CONSUMER_DLT,
        "dlt",
        context,
        publicationFailure,
        MessagingObservationOutcome.FAILED)
        .lowCardinalityKeyValue(
            MessagingObservationTags.DLT_DISPOSITION,
            dltDisposition(context))
        .highCardinalityKeyValue(
            MessagingObservationTags.RETRY_ATTEMPT,
            Integer.toString(context.deliveryAttempt()));
    observation.start();
    observation.error(publicationFailure);
    observation.stop();
  }

  private Observation observation(
      String name,
      String operation,
      KafkaConsumerFailureContext context,
      Exception observedFailure,
      MessagingObservationOutcome outcome
  ) {
    ConsumerRecord<?, ?> record = context.record();
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
            MessagingObservationTags.EXCEPTION_TYPE, exceptionType(observedFailure))
        .lowCardinalityKeyValue(
            MessagingObservationTags.FAILURE_CATEGORY,
            context.classification().category().name().toLowerCase(Locale.ROOT))
        .lowCardinalityKeyValue(
            MessagingObservationTags.FAILURE_RETRYABLE,
            Boolean.toString(context.classification().retryable()))
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

  private String dltDisposition(KafkaConsumerFailureContext context) {
    return context.classification().retryable() ? "retry_exhausted" : "direct";
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
