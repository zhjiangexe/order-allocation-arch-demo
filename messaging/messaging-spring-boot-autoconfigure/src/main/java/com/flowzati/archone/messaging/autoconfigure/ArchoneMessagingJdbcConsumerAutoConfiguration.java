package com.flowzati.archone.messaging.autoconfigure;

import com.flowzati.archone.messaging.consumer.common.DuplicateMessageDetector;
import com.flowzati.archone.messaging.inbox.DuplicateMessageDetectorInboxRepo;
import com.flowzati.archone.messaging.inbox.InboxRepo;
import com.flowzati.archone.messaging.jdbc.MessagingSchema;
import com.flowzati.archone.messaging.jdbc.MessagingSqlDialect;
import com.flowzati.archone.messaging.jdbc.MessagingTableNames;
import com.flowzati.archone.messaging.jdbc.MessagingTransactionTemplate;
import com.flowzati.archone.messaging.jdbc.PostgresMessagingSqlDialect;
import com.flowzati.archone.messaging.spring.consumer.jdbc.SpringJdbcMessageConsumerConfiguration;
import java.time.Clock;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Supplies PostgreSQL Inbox defaults and the transactional idempotency decorator. Attaching that
 * decorator to an actual inbound consumer remains a Gate E responsibility.
 */
@AutoConfiguration(
    after = {
        JdbcTemplateAutoConfiguration.class,
        ArchoneMessagingJdbcProducerAutoConfiguration.class
    },
    before = ArchoneMessagingJpaAutoConfiguration.class,
    afterName = {
        "com.flowzati.archone.messaging.autoconfigure.ArchoneMessagingAutoConfiguration",
        "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
        "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration"
    }
)
@ConditionalOnClass(JdbcOperations.class)
@ConditionalOnBean({JdbcOperations.class, PlatformTransactionManager.class})
@ConditionalOnProperty(
    prefix = "archone.messaging.consumer.jdbc",
    name = "enabled",
    matchIfMissing = true
)
@Import(SpringJdbcMessageConsumerConfiguration.class)
public class ArchoneMessagingJdbcConsumerAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  MessagingSqlDialect consumerMessagingSqlDialect() {
    return new PostgresMessagingSqlDialect();
  }

  @Bean
  @ConditionalOnMissingBean
  MessagingSchema consumerMessagingSchema() {
    return MessagingSchema.defaultSchema();
  }

  @Bean
  @ConditionalOnMissingBean
  MessagingTableNames consumerMessagingTableNames() {
    return MessagingTableNames.defaults();
  }

  @Bean
  @ConditionalOnMissingBean
  Clock consumerMessagingClock() {
    return Clock.systemUTC();
  }

  /** Temporary compatibility facade for application use cases that still claim Inbox directly. */
  @Bean
  @ConditionalOnMissingBean
  InboxRepo inboxRepo(
      MessagingTransactionTemplate transactionTemplate,
      DuplicateMessageDetector duplicateMessageDetector
  ) {
    return new DuplicateMessageDetectorInboxRepo(
        transactionTemplate, duplicateMessageDetector);
  }
}
