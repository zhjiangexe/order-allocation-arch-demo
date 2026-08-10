package com.flowzati.archone.stock.infrastructure.configuration;

import com.flowzati.archone.bootstrap.messaging.OrderPromisingKafkaFailurePolicyConfiguration;
import com.flowzati.archone.messaging.consumer.common.ResolvedMessageSubscription;
import com.flowzati.archone.stock.application.retry.AllocationConcurrencyExhaustedException;
import com.flowzati.archone.messaging.spring.consumer.kafka.KafkaConsumerFailureObserver;
import com.flowzati.archone.messaging.spring.consumer.kafka.KafkaDeadLetterErrorHandlerFactory;
import com.flowzati.archone.messaging.spring.consumer.kafka.KafkaSubscriptionErrorHandlerFactory;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.LockSupport;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.kafka.mock.MockConsumerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ProgrammaticKafkaContainerFeasibilityTest {

  private static final String TOPIC = "gate-a.programmatic-container";
  private static final String GROUP_ID = "gate-a-programmatic-container";

  @Test
  @DisplayName("Gate A: programmatic container 應承接 factory policy 並可安全啟停")
  void shouldCreateAProgrammaticContainerWithTheExistingOperationalPolicy() {
    MockConsumerFactory<String, String> consumerFactory = new MockConsumerFactory<>(() -> {
      MockConsumer<String, String> consumer = new MockConsumer<>(OffsetResetStrategy.EARLIEST);
      consumer.schedulePollTask(() -> {
        TopicPartition partition = new TopicPartition(TOPIC, 0);
        consumer.updateBeginningOffsets(Map.of(partition, 0L));
        consumer.rebalance(List.of(partition));
      });
      return consumer;
    });
    KafkaSubscriptionErrorHandlerFactory errorHandlerFactory =
        KafkaDeadLetterErrorHandlerFactory.perSubscription(
            mock(KafkaOperations.class),
            new OrderPromisingKafkaFailurePolicyConfiguration()
                .orderPromisingKafkaFailurePolicyResolver(),
            subscription -> KafkaConsumerFailureObserver.none());
    CommonErrorHandler errorHandler = errorHandlerFactory.create(
        new ResolvedMessageSubscription(
            "gate-a-subscriber", GROUP_ID, Map.of(TOPIC, TOPIC)))
        .orElseThrow();

    ConcurrentKafkaListenerContainerFactory<String, String> factory =
        new ConcurrentKafkaListenerContainerFactory<>();
    factory.setConsumerFactory(consumerFactory);
    factory.setCommonErrorHandler(errorHandler);
    factory.setConcurrency(1);
    factory.setMissingTopicsFatal(false);
    factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.BATCH);

    ConcurrentMessageListenerContainer<String, String> container = factory.createContainer(TOPIC);
    container.setBeanName("gate-a-programmatic-container");
    container.getContainerProperties().setGroupId(GROUP_ID);
    container.getContainerProperties().setMessageListener(
        (MessageListener<String, String>) record -> { });

    assertThat(container.getContainerProperties().getTopics()).containsExactly(TOPIC);
    assertThat(container.getGroupId()).isEqualTo(GROUP_ID);
    assertThat(container.getConcurrency()).isOne();
    assertThat(container.getContainerProperties().getAckMode())
        .isEqualTo(ContainerProperties.AckMode.BATCH);
    assertThat(container.getCommonErrorHandler()).isSameAs(errorHandler);
    assertThat(errorHandler).isInstanceOf(DefaultErrorHandler.class);

    try {
      container.start();
      awaitLifecycleState(container, true);
      awaitChildRunning(container);
      assertThat(container.isChildRunning()).isTrue();
    } finally {
      container.stop();
    }

    awaitLifecycleState(container, false);
    assertThat(container.getContainers()).allMatch(child -> !child.isRunning());
  }

  /**
   * 此 test 綁的是既有完整 {@link DefaultErrorHandler} instance；它內含
   * {@link AllocationConcurrencyExhaustedException} 的 retry 與 DLT recoverer policy。
   */
  private static void awaitLifecycleState(
      ConcurrentMessageListenerContainer<?, ?> container,
      boolean expectedRunning
  ) {
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (!hasLifecycleState(container, expectedRunning) && System.nanoTime() < deadline) {
      LockSupport.parkNanos(Duration.ofMillis(10).toNanos());
    }
    assertThat(container.isRunning()).isEqualTo(expectedRunning);
  }

  private static boolean hasLifecycleState(
      ConcurrentMessageListenerContainer<?, ?> container,
      boolean expectedRunning
  ) {
    return container.isRunning() == expectedRunning;
  }

  private static void awaitChildRunning(ConcurrentMessageListenerContainer<?, ?> container) {
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (!container.isChildRunning() && System.nanoTime() < deadline) {
      LockSupport.parkNanos(Duration.ofMillis(10).toNanos());
    }
    assertThat(container.isChildRunning()).isTrue();
  }
}
