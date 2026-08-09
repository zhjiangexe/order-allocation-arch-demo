package com.flowzati.archone.messaging.spring.jdbc;

import com.flowzati.archone.messaging.jdbc.JdbcStatementExecutor;
import com.flowzati.archone.messaging.jdbc.MessagingTransactionTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.transaction.PlatformTransactionManager;

/** Spring-only bean wiring for the framework-neutral JDBC and transaction ports. */
@Configuration(proxyBeanMethods = false)
public class SpringMessagingJdbcConfiguration {

  @Bean
  public JdbcStatementExecutor messagingJdbcStatementExecutor(JdbcOperations jdbcOperations) {
    return new SpringJdbcStatementExecutor(jdbcOperations);
  }

  @Bean
  public MessagingTransactionTemplate messagingTransactionTemplate(
      PlatformTransactionManager transactionManager
  ) {
    return new SpringMessagingTransactionTemplate(transactionManager);
  }
}
