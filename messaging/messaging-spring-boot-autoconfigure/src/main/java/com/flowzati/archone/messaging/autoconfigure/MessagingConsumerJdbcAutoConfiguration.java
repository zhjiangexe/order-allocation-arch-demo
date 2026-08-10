package com.flowzati.archone.messaging.autoconfigure;

import com.flowzati.archone.messaging.consumer.common.DuplicateMessageDetector;
import com.flowzati.archone.messaging.consumer.jdbc.SqlTableBasedDuplicateMessageDetector;
import com.flowzati.archone.messaging.consumer.jdbc.TransactionalIdempotencyMessageHandlerDecorator;
import com.flowzati.archone.messaging.jdbc.JdbcStatementExecutor;
import com.flowzati.archone.messaging.jdbc.MessagingSchema;
import com.flowzati.archone.messaging.jdbc.MessagingSqlDialect;
import com.flowzati.archone.messaging.jdbc.MessagingTableNames;
import com.flowzati.archone.messaging.jdbc.MessagingTransactionTemplate;
import java.time.Clock;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/** Composes JDBC Inbox claiming and the ordered transactional idempotency decorator. */
@AutoConfiguration(after = {MessagingCoreAutoConfiguration.class, MessagingJdbcAutoConfiguration.class})
@ConditionalOnClass(SqlTableBasedDuplicateMessageDetector.class)
@ConditionalOnBean({JdbcStatementExecutor.class, MessagingTransactionTemplate.class})
@ConditionalOnProperty(
    prefix = "archone.messaging.consumer.jdbc",
    name = "enabled",
    matchIfMissing = true
)
public class MessagingConsumerJdbcAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  DuplicateMessageDetector duplicateMessageDetector(
      JdbcStatementExecutor statementExecutor,
      MessagingSqlDialect dialect,
      MessagingSchema schema,
      MessagingTableNames tableNames,
      Clock clock
  ) {
    return new SqlTableBasedDuplicateMessageDetector(
        statementExecutor, dialect, schema, tableNames, clock);
  }

  @Bean
  @ConditionalOnMissingBean
  TransactionalIdempotencyMessageHandlerDecorator
      transactionalIdempotencyMessageHandlerDecorator(
          MessagingTransactionTemplate transactionTemplate,
          DuplicateMessageDetector duplicateMessageDetector
      ) {
    return new TransactionalIdempotencyMessageHandlerDecorator(
        transactionTemplate, duplicateMessageDetector);
  }
}
