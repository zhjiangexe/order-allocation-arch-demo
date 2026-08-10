package com.flowzati.archone.messaging.autoconfigure;

import com.flowzati.archone.messaging.consumer.common.DuplicateMessageDetector;
import com.flowzati.archone.messaging.inbox.InboxRepo;
import com.flowzati.archone.messaging.inbox.infrastructure.jpa.InboxRepoImpl;
import com.flowzati.archone.messaging.inbox.infrastructure.jpa.JpaEventInboxRepository;
import com.flowzati.archone.messaging.outbox.OutboxRepo;
import com.flowzati.archone.messaging.outbox.infrastructure.jpa.JpaOutboxRepository;
import com.flowzati.archone.messaging.outbox.infrastructure.jpa.OutboxRepoImpl;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration;
import org.springframework.context.annotation.Bean;

/** Temporary legacy JPA facades retained only for the Gate I application migration. */
@AutoConfiguration(
    after = {
        DataJpaRepositoriesAutoConfiguration.class,
        MessagingProducerJdbcAutoConfiguration.class,
        MessagingConsumerJdbcAutoConfiguration.class
    }
)
@ConditionalOnClass({
    JpaEventInboxRepository.class,
    JpaOutboxRepository.class
})
@ConditionalOnProperty(prefix = "archone.messaging.jpa", name = "enabled", matchIfMissing = true)
public class MessagingJpaCompatibilityAutoConfiguration {

  @Bean
  @ConditionalOnBean(JpaEventInboxRepository.class)
  @ConditionalOnMissingBean({InboxRepo.class, DuplicateMessageDetector.class})
  InboxRepo inboxRepo(JpaEventInboxRepository repository) {
    return new InboxRepoImpl(repository);
  }

  @Bean
  @ConditionalOnBean(JpaOutboxRepository.class)
  @ConditionalOnMissingBean
  OutboxRepo outboxRepo(JpaOutboxRepository repository) {
    return new OutboxRepoImpl(repository);
  }
}
