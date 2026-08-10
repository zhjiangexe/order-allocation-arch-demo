package com.flowzati.archone.messaging.spring.consumer.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowzati.archone.messaging.consumer.common.ResolvedMessageSubscription;
import com.flowzati.archone.messaging.kafka.KafkaMessageMapper;
import com.flowzati.archone.messaging.observation.MessagingObservationNames;
import com.flowzati.archone.messaging.observation.MessagingObservationTags;
import io.micrometer.common.KeyValues;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

class MicrometerKafkaConsumerFailureObserverTest {

  @Test
  void recordsRetryAndDltOutcomesWithoutPuttingRecordIdentityInMetricTags() {
    RecordingHandler handler = new RecordingHandler();
    ObservationRegistry registry = ObservationRegistry.create();
    registry.observationConfig().observationHandler(handler);
    MicrometerKafkaConsumerFailureObserver observer =
        new MicrometerKafkaConsumerFailureObserver(
            registry,
            KafkaConsumerObservationMetadataResolver.forSubscription(
                new ResolvedMessageSubscription(
                    "ordering-subscriber",
                    "ordering-group",
                    Map.of("ordering.order-events", "ordering-events"))));
    ConsumerRecord<String, String> record = record();
    IllegalStateException original = new IllegalStateException("handler unavailable");
    IllegalArgumentException dltFailure = new IllegalArgumentException("broker unavailable");

    observer.retryScheduled(record, original, 2, 1_000);
    observer.deadLetterPublished(record, original);
    observer.deadLetterPublicationFailed(record, original, dltFailure);

    assertThat(handler.stopped).hasSize(3);
    assertObservation(
        handler.stopped.get(0),
        MessagingObservationNames.CONSUMER_RETRY,
        "scheduled",
        IllegalStateException.class);
    assertObservation(
        handler.stopped.get(1),
        MessagingObservationNames.CONSUMER_DLT,
        "published",
        IllegalStateException.class);
    assertObservation(
        handler.stopped.get(2),
        MessagingObservationNames.CONSUMER_DLT,
        "failed",
        IllegalArgumentException.class);
    assertThat(handler.stopped.get(2).getError()).isSameAs(dltFailure);

    assertThat(tags(handler.stopped.get(0).getHighCardinalityKeyValues()))
        .containsEntry(MessagingObservationTags.MESSAGE_ID, recordId())
        .containsEntry(MessagingObservationTags.PARTITION_ID, "order-401")
        .containsEntry(MessagingObservationTags.KAFKA_PARTITION, "2")
        .containsEntry(MessagingObservationTags.KAFKA_OFFSET, "42")
        .containsEntry(MessagingObservationTags.RETRY_ATTEMPT, "2")
        .containsEntry(MessagingObservationTags.RETRY_BACKOFF_MILLIS, "1000");
  }

  private void assertObservation(
      Observation.Context context,
      String name,
      String outcome,
      Class<? extends Exception> failureType
  ) {
    assertThat(context.getName()).isEqualTo(name);
    assertThat(tags(context.getLowCardinalityKeyValues()))
        .containsEntry(MessagingObservationTags.SUBSCRIBER_ID, "ordering-subscriber")
        .containsEntry(MessagingObservationTags.LOGICAL_DESTINATION, "ordering-events")
        .containsEntry(MessagingObservationTags.MESSAGE_TYPE, "OrderPlaced.v1")
        .containsEntry(MessagingObservationTags.OUTCOME, outcome)
        .containsEntry(MessagingObservationTags.EXCEPTION_TYPE, failureType.getName())
        .doesNotContainKeys(
            MessagingObservationTags.MESSAGE_ID,
            MessagingObservationTags.PARTITION_ID,
            MessagingObservationTags.KAFKA_PARTITION,
            MessagingObservationTags.KAFKA_OFFSET,
            MessagingObservationTags.RETRY_ATTEMPT,
            MessagingObservationTags.RETRY_BACKOFF_MILLIS);
  }

  private ConsumerRecord<String, String> record() {
    ConsumerRecord<String, String> record = new ConsumerRecord<>(
        "ordering.order-events", 2, 42L, "order-401", "{}");
    record.headers().add(
        KafkaMessageMapper.LEGACY_ID_HEADER,
        recordId().getBytes(StandardCharsets.UTF_8));
    record.headers().add(
        KafkaMessageMapper.LEGACY_EVENT_TYPE_HEADER,
        "OrderPlaced.v1".getBytes(StandardCharsets.UTF_8));
    return record;
  }

  private String recordId() {
    return UUID.fromString("00000000-0000-0000-0000-000000000401").toString();
  }

  private static Map<String, String> tags(KeyValues keyValues) {
    Map<String, String> tags = new LinkedHashMap<>();
    keyValues.forEach(keyValue -> tags.put(keyValue.getKey(), keyValue.getValue()));
    return tags;
  }

  private static final class RecordingHandler implements ObservationHandler<Observation.Context> {
    private final List<Observation.Context> stopped = new ArrayList<>();

    @Override
    public void onStop(Observation.Context context) {
      stopped.add(context);
    }

    @Override
    public boolean supportsContext(Observation.Context context) {
      return context.getName() != null
          && context.getName().startsWith("archone.messaging.consumer.");
    }
  }
}
