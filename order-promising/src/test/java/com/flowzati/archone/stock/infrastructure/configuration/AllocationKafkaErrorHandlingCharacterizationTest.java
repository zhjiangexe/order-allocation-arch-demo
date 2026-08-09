package com.flowzati.archone.stock.infrastructure.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowzati.archone.messaging.spring.consumer.kafka.KafkaDeadLetterErrorHandlerFactory;
import com.flowzati.archone.messaging.spring.consumer.kafka.KafkaDeadLetterHeaders;
import com.flowzati.archone.stock.application.event.AllocationEventSubscriptions;
import com.flowzati.archone.stock.application.retry.AllocationConcurrencyExhaustedException;
import com.flowzati.archone.stock.application.retry.AllocationRetryContext;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.mock.MockProducerFactory;
import org.springframework.util.backoff.BackOffExecution;
import org.springframework.util.backoff.FixedBackOff;

/** Locks the retry classification and DLT record contract before Gate F replaces listeners. */
class AllocationKafkaErrorHandlingCharacterizationTest {

  @Test
  void keepsFourExponentialContainerRetriesForAllocationConcurrencyExhaustion() {
    BackOffExecution execution = new AllocationKafkaErrorHandlingConfiguration()
        .allocationRetryBackOff()
        .start();

    assertThat(execution.nextBackOff()).isEqualTo(1_000);
    assertThat(execution.nextBackOff()).isEqualTo(2_000);
    assertThat(execution.nextBackOff()).isEqualTo(4_000);
    assertThat(execution.nextBackOff()).isEqualTo(8_000);
    assertThat(execution.nextBackOff()).isEqualTo(BackOffExecution.STOP);
  }

  @Test
  void retriesOnlyAllocationConcurrencyExhaustionBeforePublishingToDlt() {
    DltFixture fixture = fixture();
    DefaultErrorHandler errorHandler = fixture.errorHandler();
    ConsumerRecord<String, String> record = record();

    boolean firstAttemptHandled = errorHandler.handleOne(
        exhausted(record), record, null, null);
    boolean secondAttemptHandled = errorHandler.handleOne(
        exhausted(record), record, null, null);

    assertThat(firstAttemptHandled).isFalse();
    assertThat(secondAttemptHandled).isTrue();
    assertThat(fixture.producer().history()).hasSize(1);
    assertDltRecord(fixture.producer().history().getFirst(), record);
  }

  @Test
  void sendsEveryOtherFailureDirectlyToDltWithoutAContainerRetry() {
    DltFixture fixture = fixture();
    DefaultErrorHandler errorHandler = fixture.errorHandler();
    ConsumerRecord<String, String> record = record();

    boolean handled = errorHandler.handleOne(
        new IllegalStateException("invalid contract"), record, null, null);

    assertThat(handled).isTrue();
    assertThat(fixture.producer().history()).hasSize(1);
    assertDltRecord(fixture.producer().history().getFirst(), record);
  }

  private DltFixture fixture() {
    MockProducer<String, String> producer = new MockProducer<>(
        true, null, new StringSerializer(), new StringSerializer());
    KafkaTemplate<String, String> template = new KafkaTemplate<>(
        new MockProducerFactory<>(() -> producer));
    @SuppressWarnings({"rawtypes", "unchecked"})
    KafkaOperations<Object, Object> operations = (KafkaOperations) template;
    AllocationKafkaErrorHandlingConfiguration configuration =
        new AllocationKafkaErrorHandlingConfiguration();
    DefaultErrorHandler errorHandler = KafkaDeadLetterErrorHandlerFactory.create(
        operations,
        new FixedBackOff(0, 1),
        configuration.allocationFailureClassifier(),
        configuration.deadLetterHeadersProvider());
    return new DltFixture(errorHandler, producer);
  }

  private ConsumerRecord<String, String> record() {
    ConsumerRecord<String, String> record = new ConsumerRecord<>(
        "ordering.order-events", 0, 42L, "order-1", "{\"eventId\":\"event-1\"}");
    record.headers().add("id", uuid().toString().getBytes(StandardCharsets.UTF_8));
    record.headers().add(
        "eventType", "OrderPlacedIntegrationEvent".getBytes(StandardCharsets.UTF_8));
    record.headers().add(
        "messageHeaders", "{\"correlation-id\":\"checkout-1\"}"
            .getBytes(StandardCharsets.UTF_8));
    return record;
  }

  private AllocationConcurrencyExhaustedException exhausted(
      ConsumerRecord<String, String> record
  ) {
    return new AllocationConcurrencyExhaustedException(
        new AllocationRetryContext("allocate-order", uuid(), record.key(), null),
        3,
        new RuntimeException("optimistic lock conflict"));
  }

  private void assertDltRecord(
      ProducerRecord<String, String> dlt,
      ConsumerRecord<String, String> original
  ) {
    assertThat(dlt.topic()).isEqualTo("ordering.order-events-dlt");
    assertThat(dlt.partition()).isEqualTo(original.partition());
    assertThat(dlt.key()).isEqualTo(original.key());
    assertThat(dlt.value()).isEqualTo(original.value());
    assertThat(header(dlt, "id")).isEqualTo(header(original, "id"));
    assertThat(header(dlt, "eventType")).isEqualTo(header(original, "eventType"));
    assertThat(header(dlt, "messageHeaders")).isEqualTo(header(original, "messageHeaders"));
    assertThat(new String(
        header(dlt, KafkaDeadLetterHeaders.ORIGINAL_LOGICAL_CHANNEL),
        StandardCharsets.UTF_8)).isEqualTo(original.topic());
    assertThat(new String(
        header(dlt, KafkaDeadLetterHeaders.ORIGINAL_PHYSICAL_DESTINATION),
        StandardCharsets.UTF_8)).isEqualTo(original.topic());
    assertThat(new String(
        header(dlt, KafkaDeadLetterHeaders.SUBSCRIBER_ID),
        StandardCharsets.UTF_8)).isEqualTo(AllocationEventSubscriptions.ORDER_LIFECYCLE);
    assertThat(new String(
        header(dlt, KafkaDeadLetterHeaders.CONSUMER_GROUP_ID),
        StandardCharsets.UTF_8)).isEqualTo(AllocationEventSubscriptions.ORDER_LIFECYCLE);
  }

  private byte[] header(ProducerRecord<String, String> record, String name) {
    return record.headers().lastHeader(name).value();
  }

  private byte[] header(ConsumerRecord<String, String> record, String name) {
    return record.headers().lastHeader(name).value();
  }

  private UUID uuid() {
    return UUID.randomUUID();
  }

  private record DltFixture(
      DefaultErrorHandler errorHandler,
      MockProducer<String, String> producer
  ) {
  }
}
