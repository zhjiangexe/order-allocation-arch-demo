package com.flowzati.archone.messaging.autoconfigure;

import com.flowzati.archone.messaging.inbox.InboxRepo;
import com.flowzati.archone.messaging.inbox.infrastructure.jpa.InboxRepoImpl;
import com.flowzati.archone.messaging.inbox.infrastructure.jpa.JpaEventInboxRepository;
import com.flowzati.archone.messaging.consumer.common.DuplicateMessageDetector;
import com.flowzati.archone.messaging.outbox.OutboxRepo;
import com.flowzati.archone.messaging.outbox.infrastructure.jpa.JpaOutboxRepository;
import com.flowzati.archone.messaging.outbox.infrastructure.jpa.OutboxRepoImpl;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Composes the legacy JPA Outbox bridge and the JPA Inbox fallback after Spring Data registration.
 * JDBC producer and consumer persistence are owned by their respective auto-configurations.
 */
@AutoConfiguration(
    after = DataJpaRepositoriesAutoConfiguration.class,
    afterName = "com.flowzati.archone.messaging.autoconfigure.ArchoneMessagingAutoConfiguration"
)
@ConditionalOnProperty(prefix = "archone.messaging.jpa", name = "enabled", matchIfMissing = true)
public class ArchoneMessagingJpaAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean({InboxRepo.class, DuplicateMessageDetector.class})
  InboxRepo inboxRepo(JpaEventInboxRepository repository) {
    return new InboxRepoImpl(repository);
  }

  /** Legacy query/write bridge scheduled for removal after the JDBC migration gates complete. */
  @Bean
  @ConditionalOnMissingBean
  OutboxRepo outboxRepo(JpaOutboxRepository repository) {
    return new OutboxRepoImpl(repository);
  }

}
