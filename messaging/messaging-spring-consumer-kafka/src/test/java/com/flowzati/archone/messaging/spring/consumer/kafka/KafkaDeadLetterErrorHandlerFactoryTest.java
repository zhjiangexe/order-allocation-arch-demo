package com.flowzati.archone.messaging.spring.consumer.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.flowzati.archone.messaging.consumer.common.MessageFailureCategory;
import com.flowzati.archone.messaging.consumer.common.MessageFailureClassification;
import com.flowzati.archone.messaging.consumer.common.ResolvedMessageSubscription;
import com.flowzati.archone.messaging.consumer.common.TypeBasedMessageFailureClassifier;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.mock.MockProducerFactory;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.util.backoff.FixedBackOff;

class KafkaDeadLetterErrorHandlerFactoryTest {

  @Test
  void retriesClassifiedFailureBeforePublishingOriginalEnvelopeToDlt() {
    RecordingFailureObserver observer = new RecordingFailureObserver();
    DltFixture fixture = fixture(new FixedBackOff(0, 1), exactHeadersProvider(), observer);
    ConsumerRecord<String, String> original = record();

    assertThat(fixture.errorHandler().handleOne(
        new ListenerWrapper(new TransientFailure()), original, null, null)).isFalse();
    assertThat(fixture.errorHandler().handleOne(
        new ListenerWrapper(new TransientFailure()), original, null, null)).isTrue();

    assertThat(fixture.producer().history()).hasSize(1);
    assertDltRecord(fixture.producer().history().getFirst(), original);
    assertThat(observer.retryAttempts).containsExactly(1);
    assertThat(observer.retryBackOffs).containsExactly(0L);
    assertThat(observer.published).containsExactly(original);
    assertThat(observer.publicationFailures).isEmpty();
  }

  @Test
  void publishesNonRetryableFailureDirectlyToDlt() {
    DltFixture fixture = fixture(new FixedBackOff(0, 10));
    ConsumerRecord<String, String> original = record();

    assertThat(fixture.errorHandler().handleOne(
        new IllegalArgumentException("invalid contract"), original, null, null)).isTrue();

    assertThat(fixture.producer().history()).hasSize(1);
    assertDltRecord(fixture.producer().history().getFirst(), original);
  }

  @Test
  void appendsExactLogicalPhysicalAndSubscriberIdentitiesForReplay() {
    DltFixture fixture = fixture(
        new FixedBackOff(0, 0),
        KafkaDeadLetterHeadersProvider.forSubscription(new ResolvedMessageSubscription(
            "allocation-inbox-scope",
            "allocation-kafka-group",
            Map.of("ordering.order-events", "ordering-events"))));

    assertThat(fixture.errorHandler().handleOne(
        new IllegalArgumentException("invalid contract"), record(), null, null)).isTrue();

    ProducerRecord<String, String> dlt = fixture.producer().history().getFirst();
    assertThat(textHeader(dlt, KafkaDeadLetterHeaders.ORIGINAL_LOGICAL_CHANNEL))
        .isEqualTo("ordering-events");
    assertThat(textHeader(dlt, KafkaDeadLetterHeaders.ORIGINAL_PHYSICAL_DESTINATION))
        .isEqualTo("ordering.order-events");
    assertThat(textHeader(dlt, KafkaDeadLetterHeaders.SUBSCRIBER_ID))
        .isEqualTo("allocation-inbox-scope");
    assertThat(textHeader(dlt, KafkaDeadLetterHeaders.CONSUMER_GROUP_ID))
        .isEqualTo("allocation-kafka-group");
  }

  @Test
  void recreatesOriginalRecordWithoutDeadLetterMetadata() {
    DltFixture fixture = fixture(
        new FixedBackOff(0, 0),
        exactHeadersProvider());
    ConsumerRecord<String, String> original = record();
    fixture.errorHandler().handleOne(
        new IllegalArgumentException("invalid contract"), original, null, null);
    ConsumerRecord<String, String> deadLetter = consumed(
        fixture.producer().history().getFirst());

    ProducerRecord<String, String> replay =
        new KafkaDeadLetterReplayRecordFactory().create(deadLetter);

    assertThat(replay.topic()).isEqualTo(original.topic());
    assertThat(replay.partition()).isEqualTo(original.partition());
    assertThat(replay.key()).isEqualTo(original.key());
    assertThat(replay.value()).isEqualTo(original.value());
    assertThat(header(replay, "id")).isEqualTo(header(original, "id"));
    assertThat(header(replay, "eventType")).isEqualTo(header(original, "eventType"));
    assertThat(header(replay, "messageHeaders"))
        .isEqualTo(header(original, "messageHeaders"));
    assertThat(replay.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_TOPIC)).isNull();
    assertThat(replay.headers().lastHeader(
        KafkaDeadLetterHeaders.ORIGINAL_PHYSICAL_DESTINATION)).isNull();
  }

  @Test
  void rejectsReplayWhenOriginalPhysicalIdentityIsInconsistent() {
    DltFixture fixture = fixture(
        new FixedBackOff(0, 0),
        exactHeadersProvider());
    fixture.errorHandler().handleOne(
        new IllegalArgumentException("invalid contract"), record(), null, null);
    ConsumerRecord<String, String> deadLetter = consumed(
        fixture.producer().history().getFirst());
    deadLetter.headers().remove(KafkaDeadLetterHeaders.ORIGINAL_PHYSICAL_DESTINATION);
    deadLetter.headers().add(
        KafkaDeadLetterHeaders.ORIGINAL_PHYSICAL_DESTINATION,
        bytes("another.topic"));

    assertThatThrownBy(() -> new KafkaDeadLetterReplayRecordFactory().create(deadLetter))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("DLT original topic does not match physical destination");
  }

  @Test
  void rejectsReplayWithoutRequiredDltMetadata() {
    assertThatThrownBy(() -> new KafkaDeadLetterReplayRecordFactory().create(record()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Missing Kafka DLT header: " + KafkaHeaders.DLT_ORIGINAL_TOPIC);
  }

  @Test
  void rejectsAmbiguousPhysicalDestinationsBeforeDltHandlingStarts() {
    ResolvedMessageSubscription first = new ResolvedMessageSubscription(
        "first-subscriber", "first-group", Map.of("shared.topic", "first-channel"));
    ResolvedMessageSubscription second = new ResolvedMessageSubscription(
        "second-subscriber", "second-group", Map.of("shared.topic", "second-channel"));

    assertThatThrownBy(() -> KafkaDeadLetterHeadersProvider.forSubscriptions(
        List.of(first, second)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("DLT destination belongs to multiple subscribers: shared.topic");
  }

  @Test
  void createsIndependentDltMetadataForTwoSubscribersOnTheSameTopic() {
    List<MockProducer<String, String>> producers = new ArrayList<>();
    KafkaTemplate<String, String> template = new KafkaTemplate<>(
        new MockProducerFactory<>(() -> {
          MockProducer<String, String> producer = new MockProducer<>(
              true, null, new StringSerializer(), new StringSerializer());
          producers.add(producer);
          return producer;
        }));
    @SuppressWarnings({"rawtypes", "unchecked"})
    KafkaOperations<Object, Object> operations = (KafkaOperations) template;
    KafkaConsumerFailurePolicy failurePolicy = new KafkaConsumerFailurePolicy(
        new FixedBackOff(0, 0),
        failure -> MessageFailureClassification.nonRetryable(MessageFailureCategory.CONTRACT));
    KafkaSubscriptionErrorHandlerFactory factory =
        KafkaDeadLetterErrorHandlerFactory.perSubscription(
            operations,
            KafkaConsumerFailurePolicyResolver.fixed(failurePolicy),
            subscription -> KafkaConsumerFailureObserver.none());
    ResolvedMessageSubscription first = new ResolvedMessageSubscription(
        "first-subscriber", "first-group", Map.of("shared.topic", "first-channel"));
    ResolvedMessageSubscription second = new ResolvedMessageSubscription(
        "second-subscriber", "second-group", Map.of("shared.topic", "second-channel"));
    ConsumerRecord<String, String> sharedRecord = new ConsumerRecord<>(
        "shared.topic", 0, 1L, "key", "{}");

    DefaultErrorHandler firstHandler = (DefaultErrorHandler) factory.create(first).orElseThrow();
    DefaultErrorHandler secondHandler = (DefaultErrorHandler) factory.create(second).orElseThrow();
    assertThat(firstHandler.handleOne(
        new IllegalArgumentException("first failure"), sharedRecord, null, null)).isTrue();
    assertThat(secondHandler.handleOne(
        new IllegalArgumentException("second failure"), sharedRecord, null, null)).isTrue();

    List<ProducerRecord<String, String>> history = producers.stream()
        .flatMap(producer -> producer.history().stream())
        .toList();
    assertThat(history).extracting(record ->
        textHeader(record, KafkaDeadLetterHeaders.SUBSCRIBER_ID))
        .containsExactly("first-subscriber", "second-subscriber");
    assertThat(history).extracting(record ->
        textHeader(record, KafkaDeadLetterHeaders.CONSUMER_GROUP_ID))
        .containsExactly("first-group", "second-group");
    assertThat(history).extracting(record ->
        textHeader(record, KafkaDeadLetterHeaders.ORIGINAL_LOGICAL_CHANNEL))
        .containsExactly("first-channel", "second-channel");
  }

  @Test
  void rejectsDltRecoveryWhenTheGlobalHandlerCannotIdentifyTheSubscriber() {
    KafkaDeadLetterHeadersProvider provider = exactHeadersProvider();
    ConsumerRecord<String, String> unknown = new ConsumerRecord<>(
        "unknown.topic", 0, 0L, "key", "value");

    assertThatThrownBy(() -> provider.headersFor(
        unknown, new IllegalArgumentException("invalid contract")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("No DLT subscription metadata for destination: unknown.topic");
  }

  @Test
  void doesNotReportRecoveryWhenDltPublicationFails() {
    @SuppressWarnings("unchecked")
    KafkaOperations<Object, Object> operations = mock(KafkaOperations.class);
    when(operations.send(any(ProducerRecord.class))).thenReturn(
        CompletableFuture.failedFuture(new IllegalStateException("broker unavailable")));
    RecordingFailureObserver observer = new RecordingFailureObserver();
    DefaultErrorHandler errorHandler = KafkaDeadLetterErrorHandlerFactory.create(
        operations,
        new FixedBackOff(0, 0),
        failure -> MessageFailureClassification.nonRetryable(MessageFailureCategory.CONTRACT),
        exactHeadersProvider(),
        observer);

    assertThat(errorHandler.handleOne(
        new IllegalArgumentException("invalid contract"), record(), null, null)).isFalse();
    assertThat(observer.published).isEmpty();
    assertThat(observer.publicationFailures).singleElement()
        .satisfies(failure -> assertThat(failure)
            .hasRootCauseMessage("broker unavailable"));
  }

  @Test
  void observationFailureNeverChangesDltRecoveryBehavior() {
    KafkaConsumerFailureObserver brokenObserver = new KafkaConsumerFailureObserver() {
      @Override
      public void retryScheduled(
          ConsumerRecord<?, ?> record,
          Exception failure,
          int deliveryAttempt,
          long nextBackOffMillis
      ) {
        throw new IllegalStateException("retry observation failed");
      }

      @Override
      public void deadLetterPublished(ConsumerRecord<?, ?> record, Exception originalFailure) {
        throw new IllegalStateException("DLT observation failed");
      }

      @Override
      public void deadLetterPublicationFailed(
          ConsumerRecord<?, ?> record,
          Exception originalFailure,
          Exception publicationFailure
      ) {
        throw new IllegalStateException("DLT failure observation failed");
      }
    };
    DltFixture fixture = fixture(
        new FixedBackOff(0, 1), exactHeadersProvider(), brokenObserver);
    ConsumerRecord<String, String> original = record();

    assertThat(fixture.errorHandler().handleOne(
        new ListenerWrapper(new TransientFailure()), original, null, null)).isFalse();
    assertThat(fixture.errorHandler().handleOne(
        new ListenerWrapper(new TransientFailure()), original, null, null)).isTrue();

    assertThat(fixture.producer().history()).hasSize(1);
    assertDltRecord(fixture.producer().history().getFirst(), original);
  }

  private DltFixture fixture(FixedBackOff retryBackOff) {
    return fixture(retryBackOff, exactHeadersProvider());
  }

  private KafkaDeadLetterHeadersProvider exactHeadersProvider() {
    return KafkaDeadLetterHeadersProvider.forSubscription(new ResolvedMessageSubscription(
        "allocation-inbox-scope",
        "allocation-kafka-group",
        Map.of("ordering.order-events", "ordering-events")));
  }

  private DltFixture fixture(
      FixedBackOff retryBackOff,
      KafkaDeadLetterHeadersProvider headersProvider
  ) {
    return fixture(retryBackOff, headersProvider, KafkaConsumerFailureObserver.none());
  }

  private DltFixture fixture(
      FixedBackOff retryBackOff,
      KafkaDeadLetterHeadersProvider headersProvider,
      KafkaConsumerFailureObserver failureObserver
  ) {
    MockProducer<String, String> producer = new MockProducer<>(
        true, null, new StringSerializer(), new StringSerializer());
    KafkaTemplate<String, String> template = new KafkaTemplate<>(
        new MockProducerFactory<>(() -> producer));
    @SuppressWarnings({"rawtypes", "unchecked"})
    KafkaOperations<Object, Object> operations = (KafkaOperations) template;
    TypeBasedMessageFailureClassifier classifier = TypeBasedMessageFailureClassifier.builder()
        .retryable(TransientFailure.class, MessageFailureCategory.HANDLER)
        .fallback(MessageFailureClassification.nonRetryable(MessageFailureCategory.CONTRACT))
        .build();
    return new DltFixture(
        KafkaDeadLetterErrorHandlerFactory.create(
            operations, retryBackOff, classifier, headersProvider, failureObserver),
        producer);
  }

  private ConsumerRecord<String, String> record() {
    ConsumerRecord<String, String> record = new ConsumerRecord<>(
        "ordering.order-events", 2, 42L, "order-1", "{\"eventId\":\"event-1\"}");
    record.headers().add("id", bytes("event-1"));
    record.headers().add("eventType", bytes("OrderPlacedIntegrationEvent"));
    record.headers().add("messageHeaders", bytes("{\"correlation-id\":\"checkout-1\"}"));
    return record;
  }

  private ConsumerRecord<String, String> consumed(ProducerRecord<String, String> produced) {
    ConsumerRecord<String, String> consumed = new ConsumerRecord<>(
        produced.topic(), produced.partition(), 0L, produced.key(), produced.value());
    for (Header header : produced.headers()) {
      consumed.headers().add(header.key(), header.value());
    }
    return consumed;
  }

  private void assertDltRecord(
      ProducerRecord<String, String> dlt,
      ConsumerRecord<String, String> original
  ) {
    assertThat(dlt.topic()).isEqualTo(original.topic() + "-dlt");
    assertThat(dlt.partition()).isEqualTo(original.partition());
    assertThat(dlt.key()).isEqualTo(original.key());
    assertThat(dlt.value()).isEqualTo(original.value());
    assertThat(header(dlt, "id")).isEqualTo(header(original, "id"));
    assertThat(header(dlt, "eventType")).isEqualTo(header(original, "eventType"));
    assertThat(header(dlt, "messageHeaders")).isEqualTo(header(original, "messageHeaders"));
    assertThat(new String(header(dlt, KafkaHeaders.DLT_ORIGINAL_TOPIC), StandardCharsets.UTF_8))
        .isEqualTo(original.topic());
    assertThat(ByteBuffer.wrap(header(dlt, KafkaHeaders.DLT_ORIGINAL_PARTITION)).getInt())
        .isEqualTo(original.partition());
    assertThat(ByteBuffer.wrap(header(dlt, KafkaHeaders.DLT_ORIGINAL_OFFSET)).getLong())
        .isEqualTo(original.offset());
    assertThat(textHeader(dlt, KafkaHeaders.DLT_EXCEPTION_FQCN)).isNotBlank();
    assertThat(textHeader(dlt, KafkaHeaders.DLT_EXCEPTION_MESSAGE)).isNotBlank();
    assertThat(textHeader(dlt, KafkaHeaders.DLT_EXCEPTION_STACKTRACE)).isNotBlank();
  }

  private byte[] header(ProducerRecord<String, String> record, String name) {
    return record.headers().lastHeader(name).value();
  }

  private byte[] header(ConsumerRecord<String, String> record, String name) {
    return record.headers().lastHeader(name).value();
  }

  private String textHeader(ProducerRecord<String, String> record, String name) {
    return new String(header(record, name), StandardCharsets.UTF_8);
  }

  private byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  private static final class TransientFailure extends RuntimeException {
  }

  private static final class ListenerWrapper extends RuntimeException {

    private ListenerWrapper(Throwable cause) {
      super(cause);
    }
  }

  private record DltFixture(
      DefaultErrorHandler errorHandler,
      MockProducer<String, String> producer
  ) {
  }

  private static final class RecordingFailureObserver
      implements KafkaConsumerFailureObserver {
    private final List<Integer> retryAttempts = new ArrayList<>();
    private final List<Long> retryBackOffs = new ArrayList<>();
    private final List<ConsumerRecord<?, ?>> published = new ArrayList<>();
    private final List<Exception> publicationFailures = new ArrayList<>();

    @Override
    public void retryScheduled(
        ConsumerRecord<?, ?> record,
        Exception failure,
        int deliveryAttempt,
        long nextBackOffMillis
    ) {
      retryAttempts.add(deliveryAttempt);
      retryBackOffs.add(nextBackOffMillis);
    }

    @Override
    public void deadLetterPublished(ConsumerRecord<?, ?> record, Exception originalFailure) {
      published.add(record);
    }

    @Override
    public void deadLetterPublicationFailed(
        ConsumerRecord<?, ?> record,
        Exception originalFailure,
        Exception publicationFailure
    ) {
      publicationFailures.add(publicationFailure);
    }
  }
}
