package com.flowzati.archone.messaging.spring.producer.jdbc;

import com.flowzati.archone.messaging.api.ChannelMapping;
import com.flowzati.archone.messaging.api.MessageIdGenerator;
import com.flowzati.archone.messaging.api.MessageInterceptor;
import com.flowzati.archone.messaging.api.MessageProducer;
import com.flowzati.archone.messaging.jdbc.JdbcStatementExecutor;
import com.flowzati.archone.messaging.jdbc.MessagingSchema;
import com.flowzati.archone.messaging.jdbc.MessagingSqlDialect;
import com.flowzati.archone.messaging.jdbc.MessagingTableNames;
import com.flowzati.archone.messaging.jdbc.MessagingTransactionTemplate;
import com.flowzati.archone.messaging.producer.common.MessageProducerImpl;
import com.flowzati.archone.messaging.producer.jdbc.JdbcOutboxMessageProducerImplementation;
import com.flowzati.archone.messaging.producer.jdbc.MessageHeadersCodec;
import com.flowzati.archone.messaging.producer.jdbc.OutboxMessageMapper;
import com.flowzati.archone.messaging.spring.jdbc.SpringMessagingJdbcConfiguration;
import java.time.Clock;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * Spring composition root for a JDBC Outbox producer. Protocol-specific defaults, such as which
 * headers represent aggregate identity, remain the importing application's responsibility. This
 * is import-only and intentionally has no component stereotype.
 */
@Import(SpringMessagingJdbcConfiguration.class)
public class SpringJdbcMessageProducerConfiguration {

  @Bean
  public JdbcOutboxMessageProducerImplementation jdbcOutboxMessageProducerImplementation(
      JdbcStatementExecutor statementExecutor,
      MessagingSqlDialect dialect,
      MessagingSchema schema,
      MessagingTableNames tableNames,
      OutboxMessageMapper messageMapper,
      MessageHeadersCodec headersCodec
  ) {
    return new JdbcOutboxMessageProducerImplementation(
        statementExecutor,
        dialect,
        schema,
        tableNames,
        messageMapper,
        headersCodec);
  }

  @Bean
  public CallerTransactionRequiredMessageProducerImplementation
      callerTransactionRequiredMessageProducerImplementation(
          MessagingTransactionTemplate transactionTemplate,
          JdbcOutboxMessageProducerImplementation delegate
      ) {
    return new CallerTransactionRequiredMessageProducerImplementation(
        transactionTemplate, delegate);
  }

  @Bean
  public MessageProducer messageProducer(
      CallerTransactionRequiredMessageProducerImplementation implementation,
      ChannelMapping channelMapping,
      List<MessageInterceptor> interceptors,
      MessageIdGenerator messageIdGenerator,
      Clock clock
  ) {
    return new MessageProducerImpl(
        implementation, channelMapping, interceptors, messageIdGenerator, clock);
  }
}
