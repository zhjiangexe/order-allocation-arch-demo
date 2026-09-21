package com.flowzati.archone.messaging.spring.consumer.jdbc;

import com.flowzati.archone.messaging.consumer.common.DuplicateMessageDetector;
import com.flowzati.archone.messaging.consumer.jdbc.SqlTableBasedDuplicateMessageDetector;
import com.flowzati.archone.messaging.consumer.jdbc.TransactionalIdempotencyMessageHandlerDecorator;
import com.flowzati.archone.messaging.jdbc.JdbcStatementExecutor;
import com.flowzati.archone.messaging.jdbc.MessagingSchema;
import com.flowzati.archone.messaging.jdbc.MessagingSqlDialect;
import com.flowzati.archone.messaging.jdbc.MessagingTableNames;
import com.flowzati.archone.messaging.jdbc.MessagingTransactionTemplate;
import com.flowzati.archone.messaging.spring.jdbc.SpringMessagingJdbcConfiguration;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Fallback;
import org.springframework.context.annotation.Import;

/**
 * Import-only Spring composition for JDBC Inbox claims and transactional idempotency. It does not
 * create a Kafka listener or attach the decorator to an application handler chain.
 */
@Import(SpringMessagingJdbcConfiguration.class)
public class SpringJdbcMessageConsumerConfiguration {

    @Bean
    @Fallback
    public SqlTableBasedDuplicateMessageDetector sqlTableBasedDuplicateMessageDetector(
            JdbcStatementExecutor statementExecutor,
            MessagingSqlDialect dialect,
            MessagingSchema schema,
            MessagingTableNames tableNames,
            Clock clock) {
        return new SqlTableBasedDuplicateMessageDetector(statementExecutor, dialect, schema, tableNames, clock);
    }

    @Bean
    public TransactionalIdempotencyMessageHandlerDecorator transactionalIdempotencyMessageHandlerDecorator(
            MessagingTransactionTemplate transactionTemplate, DuplicateMessageDetector duplicateMessageDetector) {
        return new TransactionalIdempotencyMessageHandlerDecorator(transactionTemplate, duplicateMessageDetector);
    }
}
