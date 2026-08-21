package com.flowzati.archone.inventory.allocation.entrypoint.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowzati.archone.ArchoneApplication;
import com.flowzati.archone.contracts.ordering.v1.OrderPlacedIntegrationEvent;
import com.flowzati.archone.inventory.allocation.application.event.AllocationEventSubscriptions;
import com.flowzati.archone.logisticsdata.domain.aggregate.Owner;
import com.flowzati.archone.logisticsdata.domain.repository.OwnerRepository;
import com.flowzati.archone.messaging.api.Message;
import com.flowzati.archone.messaging.api.MessageBuilder;
import com.flowzati.archone.messaging.api.MessageContext;
import com.flowzati.archone.messaging.api.MessageProducer;
import com.flowzati.archone.messaging.consumer.common.MessageHandlerDecoratorChain;
import com.flowzati.archone.messaging.consumer.common.MessageHandlerInvocation;
import com.flowzati.archone.messaging.consumer.common.MessageProcessingStatus;
import com.flowzati.archone.messaging.consumer.common.OutcomeMessageHandler;
import com.flowzati.archone.messaging.consumer.jdbc.TransactionalIdempotencyMessageHandlerDecorator;
import com.flowzati.archone.messaging.events.EventMessageHeaders;
import com.flowzati.archone.messaging.spring.optimisticlocking.OptimisticLockingDecorator;
import com.flowzati.archone.messaging.spring.optimisticlocking.OptimisticLockingRetryExhaustedException;
import com.flowzati.archone.testsupport.PostgreSQLTestConfiguration;
import com.flowzati.archone.testsupport.SitDatabase;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(
        classes = ArchoneApplication.class,
        properties = "spring.kafka.listener.auto-startup=false",
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Import(PostgreSQLTestConfiguration.class)
class AllocationTransactionalMessageChainIntegrationTest {

    @Autowired
    private OptimisticLockingDecorator optimisticLockingDecorator;

    @Autowired
    private TransactionalIdempotencyMessageHandlerDecorator transactionalDecorator;

    @Autowired
    private OwnerRepository ownerRepository;

    @Autowired
    private MessageProducer messageProducer;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void clearDatabase() {
        SitDatabase.clear(jdbcTemplate);
    }

    @Test
    @DisplayName("application retry 每次應重進 transactional Inbox chain 並只提交最後一次")
    void shouldRetryTheWholeTransactionalIdempotencyChain() {
        UUID inboundMessageId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID outboxMessageId = UUID.randomUUID();
        AtomicInteger attempts = new AtomicInteger();
        List<Long> transactionIds = new CopyOnWriteArrayList<>();
        MessageHandlerDecoratorChain chain = chain((invocation) -> {
            transactionIds.add(jdbcTemplate.queryForObject("SELECT txid_current()", Long.class));
            persistBusinessAndOutbox(ownerId, outboxMessageId);
            if (attempts.incrementAndGet() <= 2) {
                throw new OptimisticLockingFailureException("forced conflict");
            }
            return MessageProcessingStatus.PROCESSED;
        });
        MessageHandlerInvocation invocation = invocation(inboundMessageId);

        MessageProcessingStatus outcome = chain.invokeNext(invocation);

        assertThat(outcome).isEqualTo(MessageProcessingStatus.PROCESSED);
        assertThat(attempts).hasValue(3);
        assertThat(transactionIds).hasSize(3).doesNotHaveDuplicates();
        assertThat(count("event_inbox", "event_id", inboundMessageId)).isOne();
        assertThat(count("owners", "id", ownerId)).isOne();
        assertThat(count("event_outbox", "id", outboxMessageId)).isOne();

        assertThat(chain.invokeNext(invocation)).isEqualTo(MessageProcessingStatus.DUPLICATE);
        assertThat(attempts).hasValue(3);
    }

    @Test
    @DisplayName("retry 耗盡時 Inbox、JPA business 與 JDBC Outbox 應全部 rollback")
    void shouldRollbackEveryFailedAttemptWhenRetryIsExhausted() {
        UUID inboundMessageId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID outboxMessageId = UUID.randomUUID();
        AtomicInteger attempts = new AtomicInteger();
        List<Long> transactionIds = new CopyOnWriteArrayList<>();
        MessageHandlerDecoratorChain chain = chain((invocation) -> {
            transactionIds.add(jdbcTemplate.queryForObject("SELECT txid_current()", Long.class));
            persistBusinessAndOutbox(ownerId, outboxMessageId);
            attempts.incrementAndGet();
            throw new OptimisticLockingFailureException("forced conflict");
        });

        assertThatThrownBy(() -> chain.invokeNext(invocation(inboundMessageId)))
                .isInstanceOf(OptimisticLockingRetryExhaustedException.class);

        assertThat(attempts).hasValue(3);
        assertThat(transactionIds).hasSize(3).doesNotHaveDuplicates();
        assertThat(count("event_inbox", "event_id", inboundMessageId)).isZero();
        assertThat(count("owners", "id", ownerId)).isZero();
        assertThat(count("event_outbox", "id", outboxMessageId)).isZero();
    }

    private MessageHandlerDecoratorChain chain(OutcomeMessageHandler terminal) {
        return MessageHandlerDecoratorChain.create(
                List.of(transactionalDecorator, optimisticLockingDecorator), terminal);
    }

    private MessageHandlerInvocation invocation(UUID messageId) {
        Message message = MessageBuilder.withPayload("{}")
                .withId(messageId)
                .withType(OrderPlacedIntegrationEvent.EVENT_TYPE)
                .withPartitionId("order-1")
                .build();
        return new MessageHandlerInvocation(
                message,
                new MessageContext(AllocationEventSubscriptions.ORDER_PLACEMENT_DRIVER, "ordering.order-events", 1));
    }

    private void persistBusinessAndOutbox(UUID ownerId, UUID outboxMessageId) {
        ownerRepository.save(new Owner(ownerId, "GATE-E-" + ownerId, "Gate E retry owner"));
        messageProducer.send(
                "gate-e.retry-probes",
                MessageBuilder.withPayload("{}")
                        .withId(outboxMessageId)
                        .withType("GateERetryProbe.v1")
                        .withPartitionId(ownerId.toString())
                        .withHeader(EventMessageHeaders.EVENT_TYPE, "GateERetryProbe.v1")
                        .withHeader(EventMessageHeaders.EVENT_AGGREGATE_TYPE, "GateERetryProbe")
                        .withHeader(EventMessageHeaders.EVENT_AGGREGATE_ID, ownerId.toString())
                        .withHeader(EventMessageHeaders.EVENT_CONTRACT_VERSION, "1")
                        .build());
    }

    private int count(String table, String column, UUID id) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?", Integer.class, id);
    }
}
