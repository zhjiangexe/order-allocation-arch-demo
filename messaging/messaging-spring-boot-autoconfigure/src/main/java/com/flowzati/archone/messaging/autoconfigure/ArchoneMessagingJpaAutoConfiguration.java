package com.flowzati.archone.messaging.autoconfigure;

import com.flowzati.archone.messaging.api.MessageProducer;
import com.flowzati.archone.messaging.events.DefaultIntegrationEventPublisher;
import com.flowzati.archone.messaging.events.IntegrationEventPublisher;
import com.flowzati.archone.messaging.events.IntegrationEventSerializer;
import com.flowzati.archone.messaging.inbox.InboxRepo;
import com.flowzati.archone.messaging.inbox.infrastructure.jpa.InboxRepoImpl;
import com.flowzati.archone.messaging.inbox.infrastructure.jpa.JpaEventInboxRepository;
import com.flowzati.archone.messaging.outbox.OutboxMessageProducer;
import com.flowzati.archone.messaging.outbox.OutboxRepo;
import com.flowzati.archone.messaging.outbox.infrastructure.jpa.JpaOutboxRepository;
import com.flowzati.archone.messaging.outbox.infrastructure.jpa.OutboxRepoImpl;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration;
import org.springframework.context.annotation.Bean;

/** Composes JPA Inbox and Outbox beans after Spring Data repository registration. */
@AutoConfiguration(
    after = DataJpaRepositoriesAutoConfiguration.class,
    afterName = "com.flowzati.archone.messaging.autoconfigure.ArchoneMessagingAutoConfiguration"
)
@ConditionalOnProperty(prefix = "archone.messaging.jpa", name = "enabled", matchIfMissing = true)
public class ArchoneMessagingJpaAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  InboxRepo inboxRepo(JpaEventInboxRepository repository) {
    return new InboxRepoImpl(repository);
  }

  /**
   * The producer path remains synchronous in-process until the Outbox row is committed; Debezium
   * owns the later Outbox-to-Kafka delivery.
   */
  @Bean
  @ConditionalOnMissingBean
  OutboxRepo outboxRepo(JpaOutboxRepository repository) {
    return new OutboxRepoImpl(repository);
  }

  @Bean
  @ConditionalOnMissingBean
  MessageProducer messageProducer(OutboxRepo outboxRepo) {
    return new OutboxMessageProducer(outboxRepo);
  }

  @Bean
  @ConditionalOnMissingBean
  IntegrationEventPublisher integrationEventPublisher(
      MessageProducer messageProducer,
      IntegrationEventSerializer serializer
  ) {
    return new DefaultIntegrationEventPublisher(messageProducer, serializer);
  }
}
