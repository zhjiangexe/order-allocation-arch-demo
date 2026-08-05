package com.flowzati.archone.common.messaging;

import com.flowzati.archone.common.IdGenerator;
import com.flowzati.archone.common.inbox.InboxRepo;
import com.flowzati.archone.common.inbox.InboxRepoImpl;
import com.flowzati.archone.common.inbox.JpaEventInboxRepository;
import com.flowzati.archone.common.inbox.MessageMetadata;
import com.flowzati.archone.common.outbox.Outbox;
import com.flowzati.archone.common.outbox.OutboxAggregateTypes;
import com.flowzati.archone.common.outbox.OutboxAppender;
import com.flowzati.archone.common.outbox.OutboxRepo;
import com.flowzati.archone.common.outbox.infrastructure.entity.OutboxEntity;
import com.flowzati.archone.common.outbox.infrastructure.repository.JpaOutboxRepository;
import com.flowzati.archone.common.outbox.infrastructure.repository.OutboxRepoImpl;
import com.flowzati.archone.ordering.application.event.OrderingEventTopics;
import com.flowzati.archone.ordering.application.event.translator.OrderingDomainEventTranslator;
import com.flowzati.archone.ordering.domain.event.LineSnapshot;
import com.flowzati.archone.ordering.domain.event.OrderPlaced;
import com.flowzati.archone.testsupport.OrderFixtures;
import com.flowzati.archone.testsupport.PostgreSQLTestConfiguration;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
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

@DataJpaTest(properties = "spring.data.jpa.repositories.enabled=false", showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@ActiveProfiles("test")
@Import({
    PostgreSQLTestConfiguration.class,
    InboxRepoImpl.class,
    OutboxRepoImpl.class,
    OutboxAppender.class,
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
  private ApplicationEventPublisher eventPublisher;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Test
  @DisplayName("同一事件應只成功 claim 一次並保存事件類型")
  void shouldClaimInboxEventOnlyOnceAndPersistItsType() {
    UUID eventId = UUID.randomUUID();

    MessageMetadata message = new MessageMetadata(eventId, "ConfirmStockReceiptRequest");
    assertThat(inboxRepo.claimIfNew(message)).isTrue();
    assertThat(inboxRepo.claimIfNew(message)).isFalse();
    assertThat(jpaEventInboxRepository.findById(eventId)).hasValueSatisfying(row ->
        assertThat(row.getEventType()).isEqualTo("ConfirmStockReceiptRequest"));
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
      eventPublisher.publishEvent(new OrderPlaced(
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
