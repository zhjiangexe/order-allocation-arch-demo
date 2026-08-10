package com.flowzati.archone.e2e.messaging;

import com.flowzati.archone.contracts.ordering.v1.OrderPlacedIntegrationEvent;
import com.flowzati.archone.contracts.promising.v1.OrderAllocatedIntegrationEvent;
import com.flowzati.archone.messaging.spring.consumer.kafka.KafkaDeadLetterHeaders;
import com.flowzati.archone.messaging.spring.consumer.kafka.KafkaDeadLetterReplayRecordFactory;
import com.flowzati.archone.ordering.application.event.OrderingEventSubscriptions;
import com.flowzati.archone.stock.application.event.AllocationEventSubscriptions;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.kafka.support.KafkaHeaders;

import static com.flowzati.archone.e2e.messaging.FullPathMessagingEnvironment.FAILURE_FLOW_TIMEOUT;
import static com.flowzati.archone.e2e.messaging.FullPathMessagingEnvironment.NORMAL_FLOW_TIMEOUT;
import static com.flowzati.archone.e2e.messaging.FullPathMessagingEnvironment.OPTIMISTIC_TRANSACTION_ATTEMPTS;
import static com.flowzati.archone.e2e.messaging.FullPathMessagingEnvironment.ORDER_EVENTS;
import static com.flowzati.archone.e2e.messaging.FullPathMessagingEnvironment.awaitConnectorRunning;
import static com.flowzati.archone.e2e.messaging.FullPathMessagingEnvironment.awaitEvent;
import static com.flowzati.archone.e2e.messaging.FullPathMessagingEnvironment.awaitOrderStatus;
import static com.flowzati.archone.e2e.messaging.FullPathMessagingEnvironment.awaitOutboxEvent;
import static com.flowzati.archone.e2e.messaging.FullPathMessagingEnvironment.copyOf;
import static com.flowzati.archone.e2e.messaging.FullPathMessagingEnvironment.duplicateOutcomeCount;
import static com.flowzati.archone.e2e.messaging.FullPathMessagingEnvironment.failureInjector;
import static com.flowzati.archone.e2e.messaging.FullPathMessagingEnvironment.inboxClaimCount;
import static com.flowzati.archone.e2e.messaging.FullPathMessagingEnvironment.intHeader;
import static com.flowzati.archone.e2e.messaging.FullPathMessagingEnvironment.longHeader;
import static com.flowzati.archone.e2e.messaging.FullPathMessagingEnvironment.moveCount;
import static com.flowzati.archone.e2e.messaging.FullPathMessagingEnvironment.moveLineQuantity;
import static com.flowzati.archone.e2e.messaging.FullPathMessagingEnvironment.orderStatus;
import static com.flowzati.archone.e2e.messaging.FullPathMessagingEnvironment.outboxCount;
import static com.flowzati.archone.e2e.messaging.FullPathMessagingEnvironment.pauseKafka;
import static com.flowzati.archone.e2e.messaging.FullPathMessagingEnvironment.pickingCount;
import static com.flowzati.archone.e2e.messaging.FullPathMessagingEnvironment.placeOrder;
import static com.flowzati.archone.e2e.messaging.FullPathMessagingEnvironment.probeAtEnd;
import static com.flowzati.archone.e2e.messaging.FullPathMessagingEnvironment.publish;
import static com.flowzati.archone.e2e.messaging.FullPathMessagingEnvironment.reservedQuantity;
import static com.flowzati.archone.e2e.messaging.FullPathMessagingEnvironment.restartApplication;
import static com.flowzati.archone.e2e.messaging.FullPathMessagingEnvironment.seedAvailableStock;
import static com.flowzati.archone.e2e.messaging.FullPathMessagingEnvironment.startConnector;
import static com.flowzati.archone.e2e.messaging.FullPathMessagingEnvironment.startFullPath;
import static com.flowzati.archone.e2e.messaging.FullPathMessagingEnvironment.stopConnector;
import static com.flowzati.archone.e2e.messaging.FullPathMessagingEnvironment.stopFullPath;
import static com.flowzati.archone.e2e.messaging.FullPathMessagingEnvironment.textHeader;
import static com.flowzati.archone.e2e.messaging.FullPathMessagingEnvironment.unpauseKafkaIfNecessary;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Production-shaped messaging correctness specifications.
 *
 * <p>Unlike the SIT suite, these tests never drain Outbox rows in-process. A real business HTTP
 * transaction is relayed by PostgreSQL WAL and Debezium, handled through Kafka and Inbox, and may
 * produce a second Outbox event that travels through the same path back to Ordering.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FullPathMessagingCorrectnessE2eTest {

  @BeforeAll
  static void startInfrastructureAndApplication() {
    startFullPath();
  }

  @AfterAll
  static void stopInfrastructureAndApplication() {
    stopFullPath();
  }

  @BeforeEach
  void resetFaultInjection() {
    failureInjector().reset();
  }

  @Test
  @Order(1)
  @DisplayName("business transaction 應走完整 CDC 路徑，app restart 後重送仍只產生一次效果")
  void shouldCompleteFullBusinessPathAndRemainIdempotentAcrossApplicationRestart() {
    String sku = "E2E-IDEMPOTENT";
    int quantity = 3;
    seedAvailableStock(sku, 20);

    try (KafkaConsumer<String, String> orderProbe = probeAtEnd(ORDER_EVENTS)) {
      UUID orderId = placeOrder(sku, quantity, "E2E-IDEMPOTENT-1");
      UUID placedEventId = awaitOutboxEvent(orderId, OrderPlacedIntegrationEvent.EVENT_TYPE);
      ConsumerRecord<String, String> original = awaitEvent(
          orderProbe, placedEventId, NORMAL_FLOW_TIMEOUT);

      awaitOrderStatus(orderId, "ALLOCATED", NORMAL_FLOW_TIMEOUT);
      UUID allocatedEventId = awaitOutboxEvent(
          orderId, OrderAllocatedIntegrationEvent.EVENT_TYPE);
      assertCompletedExactlyOnce(orderId, sku, quantity, placedEventId, allocatedEventId);

      restartApplication();
      double duplicateBefore = duplicateOutcomeCount();
      publish(copyOf(original));
      FullPathMessagingEnvironment.awaitCondition(
          "duplicate delivery to be observed after application restart",
          NORMAL_FLOW_TIMEOUT,
          () -> duplicateOutcomeCount() > duplicateBefore);

      assertCompletedExactlyOnce(orderId, sku, quantity, placedEventId, allocatedEventId);
    }
  }

  @Test
  @Order(2)
  @DisplayName("Connect restart 後應從 PostgreSQL WAL／Kafka offset 追上停機期間的 Outbox")
  void shouldCatchUpFromWalAndContinueAfterConnectorRestart() {
    String delayedSku = "E2E-CONNECT-DOWN";
    seedAvailableStock(delayedSku, 20);

    stopConnector();
    try {
      UUID delayedOrder = placeOrder(delayedSku, 2, "E2E-CONNECT-DOWN-1");
      UUID delayedPlacedEvent = awaitOutboxEvent(
          delayedOrder, OrderPlacedIntegrationEvent.EVENT_TYPE);

      assertThat(orderStatus(delayedOrder)).isEqualTo("PENDING");
      assertThat(inboxClaimCount(
          AllocationEventSubscriptions.ORDER_LIFECYCLE, delayedPlacedEvent)).isZero();

      startConnector();
      awaitOrderStatus(delayedOrder, "ALLOCATED", NORMAL_FLOW_TIMEOUT);

      String resumedSku = "E2E-CONNECT-RESUMED";
      seedAvailableStock(resumedSku, 20);
      UUID resumedOrder = placeOrder(resumedSku, 2, "E2E-CONNECT-RESUMED-1");
      awaitOrderStatus(resumedOrder, "ALLOCATED", NORMAL_FLOW_TIMEOUT);
    } finally {
      startConnector();
    }
  }

  @Test
  @Order(3)
  @DisplayName("Kafka 暫停期間 business commit 不應遺失，恢復後應由 CDC 與 consumer 追上")
  void shouldCatchUpAfterKafkaPauseWithoutLosingTheBusinessCommit() {
    String sku = "E2E-KAFKA-PAUSE";
    seedAvailableStock(sku, 20);

    pauseKafka();
    UUID orderId;
    UUID placedEventId;
    try {
      orderId = placeOrder(sku, 4, "E2E-KAFKA-PAUSE-1");
      placedEventId = awaitOutboxEvent(orderId, OrderPlacedIntegrationEvent.EVENT_TYPE);
      assertThat(orderStatus(orderId)).isEqualTo("PENDING");
      assertThat(inboxClaimCount(
          AllocationEventSubscriptions.ORDER_LIFECYCLE, placedEventId)).isZero();
    } finally {
      unpauseKafkaIfNecessary();
    }

    awaitConnectorRunning();
    awaitOrderStatus(orderId, "ALLOCATED", FAILURE_FLOW_TIMEOUT);
    assertThat(inboxClaimCount(
        AllocationEventSubscriptions.ORDER_LIFECYCLE, placedEventId)).isOne();
  }

  @Test
  @Order(4)
  @DisplayName("handler retry 耗盡應完整 rollback 並送 DLT，修復後 replay 應成功")
  void shouldRollbackEveryFailedAttemptPreserveDltMetadataAndRecoverByReplay() {
    String sku = "E2E-DLT-REPLAY";
    int quantity = 5;
    seedAvailableStock(sku, 20);
    failureInjector().failNext(OPTIMISTIC_TRANSACTION_ATTEMPTS);

    try (KafkaConsumer<String, String> orderProbe = probeAtEnd(ORDER_EVENTS);
        KafkaConsumer<String, String> dltProbe = probeAtEnd(ORDER_EVENTS + "-dlt")) {
      UUID orderId = placeOrder(sku, quantity, "E2E-DLT-REPLAY-1");
      UUID placedEventId = awaitOutboxEvent(orderId, OrderPlacedIntegrationEvent.EVENT_TYPE);
      ConsumerRecord<String, String> original = awaitEvent(
          orderProbe, placedEventId, NORMAL_FLOW_TIMEOUT);
      ConsumerRecord<String, String> deadLetter = awaitEvent(
          dltProbe, placedEventId, FAILURE_FLOW_TIMEOUT);

      assertThat(failureInjector().invocations())
          .as("3 local attempts x 5 Kafka deliveries")
          .isEqualTo(OPTIMISTIC_TRANSACTION_ATTEMPTS);
      assertFailedAllocationRolledBack(orderId, sku, placedEventId);
      assertDeadLetterPreserves(original, deadLetter);

      failureInjector().allowSuccess();
      publish(new KafkaDeadLetterReplayRecordFactory().create(deadLetter));

      awaitOrderStatus(orderId, "ALLOCATED", NORMAL_FLOW_TIMEOUT);
      UUID allocatedEventId = awaitOutboxEvent(
          orderId, OrderAllocatedIntegrationEvent.EVENT_TYPE);
      assertCompletedExactlyOnce(orderId, sku, quantity, placedEventId, allocatedEventId);
    } finally {
      failureInjector().allowSuccess();
    }
  }

  private static void assertCompletedExactlyOnce(
      UUID orderId,
      String sku,
      int quantity,
      UUID placedEventId,
      UUID allocatedEventId
  ) {
    assertThat(orderStatus(orderId)).isEqualTo("ALLOCATED");
    assertThat(outboxCount(orderId)).isEqualTo(2);
    assertThat(inboxClaimCount(
        AllocationEventSubscriptions.ORDER_LIFECYCLE, placedEventId)).isOne();
    assertThat(inboxClaimCount(
        OrderingEventSubscriptions.ALLOCATION_RESULTS, allocatedEventId)).isOne();
    assertThat(reservedQuantity(sku)).isEqualTo(quantity);
    assertThat(pickingCount(orderId)).isOne();
    assertThat(moveCount(orderId)).isOne();
    assertThat(moveLineQuantity(orderId)).isEqualTo(quantity);
  }

  private static void assertFailedAllocationRolledBack(
      UUID orderId,
      String sku,
      UUID placedEventId
  ) {
    assertThat(orderStatus(orderId)).isEqualTo("PENDING");
    assertThat(inboxClaimCount(
        AllocationEventSubscriptions.ORDER_LIFECYCLE, placedEventId)).isZero();
    assertThat(outboxCount(orderId)).isOne();
    assertThat(reservedQuantity(sku)).isZero();
    assertThat(pickingCount(orderId)).isZero();
    assertThat(moveCount(orderId)).isZero();
    assertThat(moveLineQuantity(orderId)).isZero();
  }

  private static void assertDeadLetterPreserves(
      ConsumerRecord<String, String> original,
      ConsumerRecord<String, String> deadLetter
  ) {
    assertThat(deadLetter.topic()).isEqualTo(original.topic() + "-dlt");
    assertThat(deadLetter.key()).isEqualTo(original.key());
    assertThat(deadLetter.value()).isEqualTo(original.value());
    assertThat(textHeader(deadLetter, "id")).isEqualTo(textHeader(original, "id"));
    assertThat(textHeader(deadLetter, "eventType"))
        .isEqualTo(textHeader(original, "eventType"));
    assertThat(textHeader(deadLetter, "messageHeaders"))
        .isEqualTo(textHeader(original, "messageHeaders"));
    assertThat(textHeader(deadLetter, KafkaHeaders.DLT_ORIGINAL_TOPIC))
        .isEqualTo(original.topic());
    assertThat(intHeader(deadLetter, KafkaHeaders.DLT_ORIGINAL_PARTITION))
        .isEqualTo(original.partition());
    assertThat(longHeader(deadLetter, KafkaHeaders.DLT_ORIGINAL_OFFSET))
        .isEqualTo(original.offset());
    assertThat(textHeader(deadLetter, KafkaDeadLetterHeaders.ORIGINAL_LOGICAL_CHANNEL))
        .isEqualTo(ORDER_EVENTS);
    assertThat(textHeader(deadLetter, KafkaDeadLetterHeaders.ORIGINAL_PHYSICAL_DESTINATION))
        .isEqualTo(ORDER_EVENTS);
    assertThat(textHeader(deadLetter, KafkaDeadLetterHeaders.SUBSCRIBER_ID))
        .isEqualTo(AllocationEventSubscriptions.ORDER_LIFECYCLE);
    assertThat(textHeader(deadLetter, KafkaDeadLetterHeaders.CONSUMER_GROUP_ID))
        .isEqualTo(AllocationEventSubscriptions.ORDER_LIFECYCLE);
  }
}
