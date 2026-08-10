package com.flowzati.archone.bootstrap.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowzati.archone.messaging.consumer.common.ResolvedMessageSubscription;
import com.flowzati.archone.messaging.spring.consumer.kafka.KafkaConsumerFailureObserver;
import com.flowzati.archone.messaging.spring.consumer.kafka.KafkaConsumerFailurePolicy;
import com.flowzati.archone.messaging.spring.consumer.kafka.KafkaDeadLetterErrorHandlerFactory;
import com.flowzati.archone.messaging.spring.consumer.kafka.KafkaDeadLetterHeaders;
import com.flowzati.archone.messaging.spring.consumer.kafka.KafkaSubscriptionErrorHandlerFactory;
import com.flowzati.archone.stock.application.event.AllocationEventSubscriptions;
import com.flowzati.archone.stock.application.event.InventoryEventTopics;
import com.flowzati.archone.stock.application.retry.AllocationConcurrencyExhaustedException;
import com.flowzati.archone.stock.application.retry.AllocationRetryContext;
import java.nio.charset.StandardCharsets;
import java.util.Map;
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

class OrderPromisingKafkaFailurePolicyConfigurationTest {

  @Test
  void retriesOnlyExhaustedAllocationConcurrency() {
    KafkaConsumerFailurePolicy policy = policy();

    assertThat(policy.failureClassifier().classify(exhausted()).retryable()).isTrue();
    assertThat(policy.failureClassifier()
        .classify(new IllegalArgumentException("invalid contract"))
        .retryable()).isFalse();
  }

  @Test
  void retainsFourExponentialContainerRetries() {
    BackOffExecution execution = policy().retryBackOff().start();

    assertThat(execution.nextBackOff()).isEqualTo(1_000);
    assertThat(execution.nextBackOff()).isEqualTo(2_000);
    assertThat(execution.nextBackOff()).isEqualTo(4_000);
    assertThat(execution.nextBackOff()).isEqualTo(8_000);
    assertThat(execution.nextBackOff()).isEqualTo(BackOffExecution.STOP);
  }

  @Test
  void letsTheRuntimeBindDltMetadataFromTheActualSubscription() {
    MockProducer<String, String> producer = new MockProducer<>(
        true, null, new StringSerializer(), new StringSerializer());
    KafkaTemplate<String, String> template = new KafkaTemplate<>(
        new MockProducerFactory<>(() -> producer));
    @SuppressWarnings({"rawtypes", "unchecked"})
    KafkaOperations<Object, Object> operations = (KafkaOperations) template;
    KafkaSubscriptionErrorHandlerFactory errorHandlerFactory =
        KafkaDeadLetterErrorHandlerFactory.perSubscription(
            operations,
            new OrderPromisingKafkaFailurePolicyConfiguration()
                .orderPromisingKafkaFailurePolicyResolver(),
            subscription -> KafkaConsumerFailureObserver.none());
    ResolvedMessageSubscription subscription = new ResolvedMessageSubscription(
        AllocationEventSubscriptions.INVENTORY_AVAILABILITY,
        AllocationEventSubscriptions.INVENTORY_AVAILABILITY_CONSUMER_GROUP,
        Map.of(InventoryEventTopics.STOCK_EVENTS, InventoryEventTopics.STOCK_EVENTS));
    ConsumerRecord<String, String> record = new ConsumerRecord<>(
        InventoryEventTopics.STOCK_EVENTS, 0, 42L, "sku-1", "{}");
    DefaultErrorHandler errorHandler = (DefaultErrorHandler) errorHandlerFactory
        .create(subscription)
        .orElseThrow();

    assertThat(errorHandler.handleOne(
        new IllegalArgumentException("invalid event"), record, null, null)).isTrue();

    ProducerRecord<String, String> deadLetter = producer.history().getFirst();
    assertThat(deadLetter.topic()).isEqualTo(InventoryEventTopics.STOCK_EVENTS + "-dlt");
    assertThat(textHeader(deadLetter, KafkaDeadLetterHeaders.SUBSCRIBER_ID))
        .isEqualTo(AllocationEventSubscriptions.INVENTORY_AVAILABILITY);
    assertThat(textHeader(deadLetter, KafkaDeadLetterHeaders.CONSUMER_GROUP_ID))
        .isEqualTo(AllocationEventSubscriptions.INVENTORY_AVAILABILITY_CONSUMER_GROUP);
  }

  private KafkaConsumerFailurePolicy policy() {
    return new OrderPromisingKafkaFailurePolicyConfiguration()
        .orderPromisingKafkaFailurePolicyResolver()
        .resolve(new ResolvedMessageSubscription(
            "any-order-promising-subscriber",
            "any-order-promising-group",
            Map.of("any.topic", "any-channel")));
  }

  private AllocationConcurrencyExhaustedException exhausted() {
    return new AllocationConcurrencyExhaustedException(
        new AllocationRetryContext("allocate-order", UUID.randomUUID(), "order-1", null),
        3,
        new RuntimeException("optimistic lock conflict"));
  }

  private String textHeader(ProducerRecord<String, String> record, String name) {
    return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
  }
}
