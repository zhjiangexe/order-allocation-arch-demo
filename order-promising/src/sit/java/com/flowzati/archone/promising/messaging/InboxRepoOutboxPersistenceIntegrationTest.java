package com.flowzati.archone.promising.messaging;

import com.flowzati.archone.foundation.identity.IdGenerator;
import com.flowzati.archone.catalog.infrastructure.entity.OwnerEntity;
import com.flowzati.archone.messaging.autoconfigure.ArchoneMessagingAutoConfiguration;
import com.flowzati.archone.messaging.autoconfigure.ArchoneMessagingJpaAutoConfiguration;
import com.flowzati.archone.messaging.api.MessageMetadata;
import com.flowzati.archone.messaging.inbox.InboxRepo;
import com.flowzati.archone.messaging.inbox.infrastructure.jpa.JpaEventInboxRepository;
import com.flowzati.archone.messaging.inbox.infrastructure.jpa.entity.InboxId;
import com.flowzati.archone.messaging.outbox.Outbox;
import com.flowzati.archone.promising.messaging.OutboxAggregateTypes;
import com.flowzati.archone.messaging.outbox.OutboxRepo;
import com.flowzati.archone.messaging.outbox.infrastructure.jpa.entity.OutboxEntity;
import com.flowzati.archone.messaging.outbox.infrastructure.jpa.JpaOutboxRepository;
import com.flowzati.archone.ordering.application.event.OrderingDomainEventPublisher;
import com.flowzati.archone.ordering.application.event.OrderingEventTopics;
import com.flowzati.archone.ordering.application.event.translator.OrderingDomainEventTranslator;
import com.flowzati.archone.ordering.domain.event.LineSnapshot;
import com.flowzati.archone.ordering.domain.event.OrderPlaced;
import com.flowzati.archone.testsupport.OrderFixtures;
import com.flowzati.archone.testsupport.PostgreSQLTestConfiguration;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = "spring.data.jpa.repositories.enabled=false", showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration({
    FlywayAutoConfiguration.class,
    ArchoneMessagingAutoConfiguration.class,
    ArchoneMessagingJpaAutoConfiguration.class
})
@ActiveProfiles("test")
@Import({
    PostgreSQLTestConfiguration.class,
    OrderingDomainEventTranslator.class,
    InboxRepoOutboxPersistenceIntegrationTest.JsonConfiguration.class,
    InboxRepoOutboxPersistenceIntegrationTest.RepositoryConfiguration.class
})
class InboxRepoOutboxPersistenceIntegrationTest {

  @Autowired
  private InboxRepo inboxRepo;

  @Autowired
  private OutboxRepo outboxRepo;

  @Autowired
  private JpaEventInboxRepository jpaEventInboxRepository;

  @Autowired
  private JpaOutboxRepository outboxRepository;

  @Autowired
  private PlatformTransactionManager transactionManager;

  @Autowired
  private OrderingDomainEventPublisher eventPublisher;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Autowired
  private EntityManager entityManager;

  @Test
  @DisplayName("同一 subscriber 只 claim 一次，不同 subscriber 可各處理一次")
  void shouldClaimInboxEventOncePerSubscriber() {
    UUID eventId = UUID.randomUUID();

    MessageMetadata firstSubscriber = new MessageMetadata(
        eventId, "ConfirmStockReceiptRequest", "stock-receipt-requests");
    MessageMetadata secondSubscriber = new MessageMetadata(
        eventId, "ConfirmStockReceiptRequest", "stock-audit-projection");

    assertThat(inboxRepo.claimIfNew(firstSubscriber)).isTrue();
    assertThat(inboxRepo.claimIfNew(firstSubscriber)).isFalse();
    assertThat(inboxRepo.claimIfNew(secondSubscriber)).isTrue();
    assertThat(jpaEventInboxRepository.findById(
        new InboxId(firstSubscriber.subscriberId(), eventId))).hasValueSatisfying(row -> {
          assertThat(row.getSubscriberId()).isEqualTo(firstSubscriber.subscriberId());
          assertThat(row.getEventType()).isEqualTo("ConfirmStockReceiptRequest");
        });
    assertThat(jpaEventInboxRepository.findById(
        new InboxId(secondSubscriber.subscriberId(), eventId))).isPresent();
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  @DisplayName("Inbox adapter 應拒絕脫離 business transaction 的獨立 claim")
  void shouldRequireAnExistingTransactionForInboxClaim() {
    MessageMetadata message = new MessageMetadata(
        UUID.randomUUID(), "ConfirmStockReceiptRequest", "stock-receipt-requests");

    assertThatThrownBy(() -> inboxRepo.claimIfNew(message))
        .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
  }

  @Test
  @DisplayName("Outbox 應保存不可變的完整事件 payload")
  void shouldPersistImmutableOutboxEventPayload() {
    UUID eventId = UUID.randomUUID();
    UUID orderId = IdGenerator.nextId();
    Instant occurredAt = Instant.parse("2026-07-24T10:00:00Z");
    outboxRepo.append(new Outbox(
        eventId,
        OutboxAggregateTypes.ORDER,
        orderId.toString(),
        "OrderPlacedIntegrationEvent",
        OrderingEventTopics.ORDER_EVENTS,
        "HOT-SKU",
        "{\"eventId\":\"" + eventId + "\"}",
        occurredAt
    ));

    OutboxEntity row = outboxRepository.findById(eventId).orElseThrow();
    assertThat(row.getEventType()).isEqualTo("OrderPlacedIntegrationEvent");
    assertThat(row.getRoute()).isEqualTo(OrderingEventTopics.ORDER_EVENTS);
    assertThat(row.getAggregateId()).isEqualTo(orderId.toString());
    assertThat(row.getPartitionKey()).isEqualTo("HOT-SKU");
    assertThat(row.getPayload()).contains(eventId.toString());
    assertThat(row.getOccurredAt()).isEqualTo(occurredAt);
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  @DisplayName("Outbox adapter 應拒絕脫離 caller transaction 的獨立寫入")
  void shouldRequireAnExistingTransaction() {
    UUID eventId = UUID.randomUUID();

    assertThatThrownBy(() -> outboxRepo.append(new Outbox(
        eventId,
        OutboxAggregateTypes.ORDER,
        UUID.randomUUID().toString(),
        "OrderPlacedIntegrationEvent",
        OrderingEventTopics.ORDER_EVENTS,
        "order-1",
        "{\"eventId\":\"" + eventId + "\"}",
        Instant.parse("2026-07-24T10:00:00Z"))))
        .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
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
              ship_to_address, promised_delivery_date, status, received_at, version)
          VALUES (?, ?, ?, ?, '100', '台北市中正區重慶南路一段 122 號',
                  DATE '2026-08-01', ?, ?, ?)
          """, orderId, OrderFixtures.OWNER_ID, "EXT-" + orderId, OrderFixtures.FACILITY_ID,
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
    assertThat(outboxRepository.count()).isZero();
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

  private int countById(String table, String column, UUID id) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?",
        Integer.class,
        id);
  }

  @EnableJpaRepositories(basePackageClasses = {JpaEventInboxRepository.class, JpaOutboxRepository.class})
  static class RepositoryConfiguration {
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class JsonConfiguration {
    @Bean
    com.fasterxml.jackson.databind.ObjectMapper objectMapper() {
      return new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
    }
  }
}
