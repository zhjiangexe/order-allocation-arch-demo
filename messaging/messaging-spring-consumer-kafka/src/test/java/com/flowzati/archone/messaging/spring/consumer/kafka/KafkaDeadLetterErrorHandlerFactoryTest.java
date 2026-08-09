package com.flowzati.archone.messaging.spring.consumer.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.flowzati.archone.messaging.consumer.common.MessageFailureCategory;
import com.flowzati.archone.messaging.consumer.common.MessageFailureClassification;
import com.flowzati.archone.messaging.consumer.common.TypeBasedMessageFailureClassifier;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
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
    DltFixture fixture = fixture(new FixedBackOff(0, 1));
    ConsumerRecord<String, String> original = record();

    assertThat(fixture.errorHandler().handleOne(
        new ListenerWrapper(new TransientFailure()), original, null, null)).isFalse();
    assertThat(fixture.errorHandler().handleOne(
        new ListenerWrapper(new TransientFailure()), original, null, null)).isTrue();

    assertThat(fixture.producer().history()).hasSize(1);
    assertDltRecord(fixture.producer().history().getFirst(), original);
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
  void doesNotReportRecoveryWhenDltPublicationFails() {
    @SuppressWarnings("unchecked")
    KafkaOperations<Object, Object> operations = mock(KafkaOperations.class);
    when(operations.send(any(ProducerRecord.class))).thenReturn(
        CompletableFuture.failedFuture(new IllegalStateException("broker unavailable")));
    DefaultErrorHandler errorHandler = KafkaDeadLetterErrorHandlerFactory.create(
        operations,
        new FixedBackOff(0, 0),
        failure -> MessageFailureClassification.nonRetryable(MessageFailureCategory.CONTRACT));

    assertThat(errorHandler.handleOne(
        new IllegalArgumentException("invalid contract"), record(), null, null)).isFalse();
  }

  private DltFixture fixture(FixedBackOff retryBackOff) {
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
        KafkaDeadLetterErrorHandlerFactory.create(operations, retryBackOff, classifier),
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
  }

  private byte[] header(ProducerRecord<String, String> record, String name) {
    return record.headers().lastHeader(name).value();
  }

  private byte[] header(ConsumerRecord<String, String> record, String name) {
    return record.headers().lastHeader(name).value();
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
}
