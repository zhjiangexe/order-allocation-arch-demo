package com.flowzati.archone.messaging.autoconfigure;

import com.flowzati.archone.messaging.jdbc.JdbcStatementExecutor;
import com.flowzati.archone.messaging.jdbc.MessagingSchema;
import com.flowzati.archone.messaging.jdbc.MessagingSqlDialect;
import com.flowzati.archone.messaging.jdbc.MessagingTableNames;
import com.flowzati.archone.messaging.jdbc.MessagingTransactionTemplate;
import com.flowzati.archone.messaging.jdbc.PostgresMessagingSqlDialect;
import com.flowzati.archone.messaging.spring.jdbc.SpringJdbcStatementExecutor;
import com.flowzati.archone.messaging.spring.jdbc.SpringMessagingTransactionTemplate;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.transaction.PlatformTransactionManager;

/** Shared JDBC and transaction-port wiring used by producer and consumer persistence. */
@AutoConfiguration(
    after = JdbcTemplateAutoConfiguration.class,
    afterName = {
        "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
        "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration"
    }
)
@ConditionalOnClass({
    JdbcOperations.class,
    JdbcStatementExecutor.class,
    SpringJdbcStatementExecutor.class
})
@ConditionalOnBean({JdbcOperations.class, PlatformTransactionManager.class})
@ConditionalOnProperty(
    prefix = "archone.messaging.jdbc",
    name = "enabled",
    matchIfMissing = true
)
public class MessagingJdbcAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  JdbcStatementExecutor messagingJdbcStatementExecutor(JdbcOperations jdbcOperations) {
    return new SpringJdbcStatementExecutor(jdbcOperations);
  }

  @Bean
  @ConditionalOnMissingBean
  MessagingTransactionTemplate messagingTransactionTemplate(
      PlatformTransactionManager transactionManager
  ) {
    return new SpringMessagingTransactionTemplate(transactionManager);
  }

  @Bean
  @ConditionalOnMissingBean
  MessagingSqlDialect messagingSqlDialect() {
    return new PostgresMessagingSqlDialect();
  }

  @Bean
  @ConditionalOnMissingBean
  MessagingSchema messagingSchema() {
    return MessagingSchema.defaultSchema();
  }

  @Bean
  @ConditionalOnMissingBean
  MessagingTableNames messagingTableNames() {
    return MessagingTableNames.defaults();
  }
}
