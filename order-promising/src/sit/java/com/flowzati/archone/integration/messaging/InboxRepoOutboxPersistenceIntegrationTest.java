package com.flowzati.archone.integration.messaging;

import com.flowzati.archone.contracts.ordering.v1.OrderingAggregateTypes;
import com.flowzati.archone.contracts.ordering.v1.OrderPlacedIntegrationEvent;
import com.flowzati.archone.foundation.identity.IdGenerator;
import com.flowzati.archone.catalog.infrastructure.entity.OwnerEntity;
import com.flowzati.archone.messaging.autoconfigure.MessagingConsumerJdbcAutoConfiguration;
import com.flowzati.archone.messaging.autoconfigure.MessagingCoreAutoConfiguration;
import com.flowzati.archone.messaging.autoconfigure.MessagingIntegrationEventPublisherAutoConfiguration;
import com.flowzati.archone.messaging.autoconfigure.MessagingJdbcAutoConfiguration;
import com.flowzati.archone.messaging.autoconfigure.MessagingProducerJdbcAutoConfiguration;
import com.flowzati.archone.messaging.api.MessageHeaders;
import com.flowzati.archone.messaging.api.MessageBuilder;
import com.flowzati.archone.messaging.api.MessageContext;
import com.flowzati.archone.messaging.api.MessageInterceptor;
import com.flowzati.archone.messaging.consumer.common.DuplicateMessageDetector;
import com.flowzati.archone.messaging.consumer.common.MessageHandlerDecoratorChain;
import com.flowzati.archone.messaging.consumer.common.MessageHandlerInvocation;
import com.flowzati.archone.messaging.consumer.common.ProcessingOutcome;
import com.flowzati.archone.messaging.consumer.jdbc.TransactionalIdempotencyMessageHandlerDecorator;
import com.flowzati.archone.messaging.events.AggregateReference;
import com.flowzati.archone.messaging.events.IntegrationEventPublisher;
import com.flowzati.archone.messaging.events.PublicationTarget;
import com.flowzati.archone.ordering.application.event.OrderingDomainEventPublisher;
import com.flowzati.archone.contracts.ordering.v1.OrderingChannels;
import com.flowzati.archone.ordering.infrastructure.messaging.producer.OrderingIntegrationEventPublisher;
import com.flowzati.archone.ordering.domain.event.LineSnapshot;
import com.flowzati.archone.ordering.domain.event.OrderPlaced;
import com.flowzati.archone.testsupport.OrderFixtures;
import com.flowzati.archone.testsupport.PostgreSQLTestConfiguration;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = "spring.data.jpa.repositories.enabled=false", showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration({
    FlywayAutoConfiguration.class,
    MessagingCoreAutoConfiguration.class,
    MessagingJdbcAutoConfiguration.class,
    MessagingProducerJdbcAutoConfiguration.class,
    MessagingConsumerJdbcAutoConfiguration.class,
    MessagingIntegrationEventPublisherAutoConfiguration.class
})
@ActiveProfiles("test")
@Import({
    PostgreSQLTestConfiguration.class,
    OrderingIntegrationEventPublisher.class,
    JdbcMessagingPersistenceIntegrationTest.JsonConfiguration.class
})
class JdbcMessagingPersistenceIntegrationTest {

  @Autowired
  private DuplicateMessageDetector duplicateMessageDetector;

  @Autowired
  private TransactionalIdempotencyMessageHandlerDecorator idempotencyDecorator;

  @Autowired
  private PlatformTransactionManager transactionManager;

  @Autowired
  private OrderingDomainEventPublisher eventPublisher;

  @Autowired
  private IntegrationEventPublisher integrationEventPublisher;

  @Autowired
  private ObjectMapper objectMapper;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Autowired
  private EntityManager entityManager;

  @Test
  @DisplayName("同一 subscriber 只 claim 一次，不同 subscriber 可各處理一次")
  void shouldClaimInboxEventOncePerSubscriber() {
    UUID eventId = UUID.randomUUID();

    String eventType = "ConfirmStockReceiptRequest";
    String firstSubscriber = "stock-receipt-requests";
    String secondSubscriber = "stock-audit-projection";

    assertThat(duplicateMessageDetector.claimIfNew(firstSubscriber, eventId, eventType)).isTrue();
    assertThat(duplicateMessageDetector.claimIfNew(firstSubscriber, eventId, eventType)).isFalse();
    assertThat(duplicateMessageDetector.claimIfNew(secondSubscriber, eventId, eventType)).isTrue();
    assertThat(inboxClaimCount(firstSubscriber, eventId)).isOne();
    assertThat(inboxClaimCount(secondSubscriber, eventId)).isOne();
    assertThat(jdbcTemplate.queryForObject("""
        SELECT event_type FROM event_inbox
         WHERE subscriber_id = ? AND event_id = ?
        """, String.class, firstSubscriber, eventId)).isEqualTo(eventType);
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  @DisplayName("atomic Inbox insert 應讓 concurrent duplicate race 只有一個 winner")
  void shouldResolveConcurrentDuplicateClaimsWithTheDatabaseConstraint() throws Exception {
    UUID eventId = UUID.randomUUID();
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);

    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<Boolean> first = executor.submit(() -> concurrentClaim(eventId, ready, start));
      Future<Boolean> second = executor.submit(() -> concurrentClaim(eventId, ready, start));
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();

      assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(true, false);
    } finally {
      jdbcTemplate.update("DELETE FROM event_inbox WHERE event_id = ?", eventId);
    }
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  @DisplayName("transactional decorator 應 rollback failed claim 並正常完成 duplicate")
  void shouldRollbackFailedDecoratorHandlingAndSkipADuplicate() {
    UUID eventId = UUID.randomUUID();
    var invocation = new MessageHandlerInvocation(
        MessageBuilder.withPayload("{}")
            .withId(eventId)
            .withType("GateDMessage.v1")
            .withPartitionId("gate-d-1")
            .build(),
        new MessageContext("gate-d-decorator", "gate-d.messages", 1));
    IllegalStateException failure = new IllegalStateException("handler failed");
    var failingChain = MessageHandlerDecoratorChain.create(
        List.of(idempotencyDecorator),
        ignored -> {
          throw failure;
        });

    assertThatThrownBy(() -> failingChain.invokeNext(invocation)).isSameAs(failure);
    assertThat(countById("event_inbox", "event_id", eventId)).isZero();

    AtomicInteger handlerCalls = new AtomicInteger();
    var successfulChain = MessageHandlerDecoratorChain.create(
        List.of(idempotencyDecorator),
        ignored -> {
          handlerCalls.incrementAndGet();
          return ProcessingOutcome.PROCESSED;
        });
    assertThat(successfulChain.invokeNext(invocation)).isEqualTo(ProcessingOutcome.PROCESSED);
    assertThat(successfulChain.invokeNext(invocation)).isEqualTo(ProcessingOutcome.DUPLICATE);
    assertThat(handlerCalls).hasValue(1);
    assertThat(countById("event_inbox", "event_id", eventId)).isOne();

    jdbcTemplate.update("DELETE FROM event_inbox WHERE event_id = ?", eventId);
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  @DisplayName("業務異動失敗時應連同翻譯後的 Outbox 一起回滾")
  void shouldRollbackBusinessChangeAndTranslatedOutboxTogether() {
    UUID orderId = IdGenerator.nextId();
    Instant receivedAt = Instant.parse("2026-07-24T10:00:00Z");
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);

    transaction.executeWithoutResult(status -> {
      OrderFixtures.seedCatalog(jdbcTemplate, OrderFixtures.OWNER_ID, "SKU-1");
      jdbcTemplate.update("""
          INSERT INTO orders (
              id, owner_id, external_order_no, facility_id, ship_to_zone,
              ship_to_address, promised_delivery_date, dispatch_by, release_priority,
              status, received_at, version)
          VALUES (?, ?, ?, ?, '100', '台北市中正區重慶南路一段 122 號',
                  DATE '2026-08-01', ?, 50, ?, ?, ?)
          """, orderId, OrderFixtures.OWNER_ID, "EXT-" + orderId, OrderFixtures.FACILITY_ID,
          Timestamp.from(OrderFixtures.DISPATCH_BY),
          "PENDING",
          Timestamp.from(receivedAt), 0L);
      eventPublisher.publish(new OrderPlaced(
          orderId,
          OrderFixtures.OWNER_ID,
          OrderFixtures.FACILITY_ID,
          "100",
          java.time.LocalDate.of(2026, 8, 1),
          java.util.List.of(new LineSnapshot(1, "SKU-1", 3)),
          receivedAt));
      status.setRollbackOnly();
    });

    assertThat(jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM orders WHERE id = ?", Integer.class, orderId)).isZero();
    assertThat(tableCount("event_outbox")).isZero();
  }

  @Test
  @DisplayName("JDBC producer 應保存 payload、key、timestamp 與 generic headers")
  void shouldPersistTheCompleteJdbcOutboxContract() throws Exception {
    UUID eventId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    Instant occurredAt = Instant.parse("2026-08-09T12:30:00Z");
    OrderPlacedIntegrationEvent event = new OrderPlacedIntegrationEvent(
        eventId, orderId, occurredAt);

    integrationEventPublisher.publish(
        event,
        new AggregateReference(OrderingAggregateTypes.ORDER, orderId.toString()),
        new PublicationTarget(OrderingChannels.ORDER_EVENTS, "HOT-SKU"),
        occurredAt);

    Map<String, Object> row = jdbcTemplate.queryForMap("""
        SELECT aggregatetype, aggregateid, type, route, partition_key,
               payload::text AS payload, timestamp, headers
          FROM event_outbox
         WHERE id = ?
        """, eventId);
    assertThat(row)
        .containsEntry("aggregatetype", OrderingAggregateTypes.ORDER)
        .containsEntry("aggregateid", orderId.toString())
        .containsEntry("type", OrderPlacedIntegrationEvent.EVENT_TYPE)
        .containsEntry("route", OrderingChannels.ORDER_EVENTS)
        .containsEntry("partition_key", "HOT-SKU");
    assertThat(((Timestamp) row.get("timestamp")).toInstant()).isEqualTo(occurredAt);
    JsonNode payload = objectMapper.readTree(row.get("payload").toString());
    assertThat(payload.path("eventId").asText()).isEqualTo(eventId.toString());
    JsonNode headers = objectMapper.readTree(row.get("headers").toString());
    assertThat(headers.path("event-type").asText())
        .isEqualTo(OrderPlacedIntegrationEvent.EVENT_TYPE);
    assertThat(headers.path("event-contract-version").asText()).isEqualTo("1");
    assertThat(headers.path(MessageHeaders.CORRELATION_ID).asText()).isEqualTo("checkout-1");
    assertThat(headers.path(MessageHeaders.CAUSATION_ID).asText()).isEqualTo("command-1");
    assertThat(headers.path(MessageHeaders.TRACEPARENT).asText())
        .isEqualTo("00-abc-def-01");
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  @DisplayName("duplicate message ID 應讓整筆 caller transaction 回滾")
  void shouldRollbackTheCallerTransactionForADuplicateMessageId() {
    UUID eventId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    Instant occurredAt = Instant.parse("2026-08-09T12:45:00Z");
    OrderPlacedIntegrationEvent event = new OrderPlacedIntegrationEvent(
        eventId, orderId, occurredAt);
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);

    assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
      integrationEventPublisher.publish(
          event,
          new AggregateReference(OrderingAggregateTypes.ORDER, orderId.toString()),
          new PublicationTarget(OrderingChannels.ORDER_EVENTS, orderId.toString()),
          occurredAt);
      integrationEventPublisher.publish(
          event,
          new AggregateReference(OrderingAggregateTypes.ORDER, orderId.toString()),
          new PublicationTarget(OrderingChannels.ORDER_EVENTS, orderId.toString()),
          occurredAt);
    }))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThat(countById("event_outbox", "id", eventId)).isZero();
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  @DisplayName("Gate A: JPA business write 與 JDBC Inbox/Outbox 應共用同一交易")
  void shouldCommitAndRollbackJpaBusinessWithJdbcInboxAndOutboxAtomically() {
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    UUID committedOwnerId = UUID.randomUUID();
    UUID committedMessageId = UUID.randomUUID();
    List<Long> committedTransactionIds = new ArrayList<>();

    transaction.executeWithoutResult(status -> appendMixedPersistenceRows(
        committedOwnerId, committedMessageId, committedTransactionIds));

    assertThat(committedTransactionIds).hasSize(2);
    assertThat(committedTransactionIds.get(0)).isEqualTo(committedTransactionIds.get(1));
    assertThat(countById("owners", "id", committedOwnerId)).isOne();
    assertThat(countById("event_inbox", "event_id", committedMessageId)).isOne();
    assertThat(countById("event_outbox", "id", committedMessageId)).isOne();

    UUID rolledBackOwnerId = UUID.randomUUID();
    UUID rolledBackMessageId = UUID.randomUUID();
    List<Long> rolledBackTransactionIds = new ArrayList<>();

    transaction.executeWithoutResult(status -> {
      appendMixedPersistenceRows(rolledBackOwnerId, rolledBackMessageId, rolledBackTransactionIds);
      status.setRollbackOnly();
    });

    assertThat(rolledBackTransactionIds).hasSize(2);
    assertThat(rolledBackTransactionIds.get(0)).isEqualTo(rolledBackTransactionIds.get(1));
    assertThat(countById("owners", "id", rolledBackOwnerId)).isZero();
    assertThat(countById("event_inbox", "event_id", rolledBackMessageId)).isZero();
    assertThat(countById("event_outbox", "id", rolledBackMessageId)).isZero();

    jdbcTemplate.update("DELETE FROM event_inbox WHERE event_id = ?", committedMessageId);
    jdbcTemplate.update("DELETE FROM event_outbox WHERE id = ?", committedMessageId);
    jdbcTemplate.update("DELETE FROM owners WHERE id = ?", committedOwnerId);
  }

  private void appendMixedPersistenceRows(
      UUID ownerId,
      UUID messageId,
      List<Long> transactionIds
  ) {
    transactionIds.add(jdbcTemplate.queryForObject("SELECT txid_current()", Long.class));
    entityManager.persist(new OwnerEntity(ownerId, "GATE-A-" + ownerId, "Gate A owner"));
    entityManager.flush();
    jdbcTemplate.update("""
        INSERT INTO event_inbox (subscriber_id, event_id, event_type, processed_at)
        VALUES ('gate-a-mixed-transaction', ?, 'GateAMessage', CURRENT_TIMESTAMP)
        """, messageId);
    jdbcTemplate.update("""
        INSERT INTO event_outbox (
            id, aggregatetype, aggregateid, type, route, partition_key, payload, timestamp)
        VALUES (?, 'GateAOwner', ?, 'GateAMessage', 'gate-a.messages', ?,
                CAST(? AS jsonb), CURRENT_TIMESTAMP)
        """, messageId, ownerId.toString(), ownerId.toString(),
        "{\"messageId\":\"" + messageId + "\"}");
    transactionIds.add(jdbcTemplate.queryForObject("SELECT txid_current()", Long.class));
  }

  private boolean concurrentClaim(
      UUID eventId,
      CountDownLatch ready,
      CountDownLatch start
  ) throws InterruptedException {
    ready.countDown();
    if (!start.await(10, TimeUnit.SECONDS)) {
      throw new IllegalStateException("Concurrent Inbox claim start gate timed out");
    }
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    return Boolean.TRUE.equals(transaction.execute(status -> duplicateMessageDetector.claimIfNew(
        "gate-d-concurrency", eventId, "GateDConcurrentMessage.v1")));
  }

  private int countById(String table, String column, UUID id) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?",
        Integer.class,
        id);
  }

  private int inboxClaimCount(String subscriberId, UUID eventId) {
    return jdbcTemplate.queryForObject("""
        SELECT COUNT(*) FROM event_inbox
         WHERE subscriber_id = ? AND event_id = ?
        """, Integer.class, subscriberId, eventId);
  }

  private int tableCount(String table) {
    return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class JsonConfiguration {
    @Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper();
    }

    @Bean
    MessageInterceptor correlationAndTraceHeaders() {
      return new MessageInterceptor() {
        @Override
        public com.flowzati.archone.messaging.api.Message preSend(
            com.flowzati.archone.messaging.api.Message message
        ) {
          return message
              .withHeader(MessageHeaders.CORRELATION_ID, "checkout-1")
              .withHeader(MessageHeaders.CAUSATION_ID, "command-1")
              .withHeader(MessageHeaders.TRACEPARENT, "00-abc-def-01");
        }
      };
    }
  }
}
