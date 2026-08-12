package com.flowzati.archone.messaging.autoconfigure;

import com.flowzati.archone.messaging.api.ChannelMapping;
import com.flowzati.archone.messaging.api.MessageIdGenerator;
import com.flowzati.archone.messaging.api.MessageInterceptor;
import com.flowzati.archone.messaging.api.MessageProducer;
import com.flowzati.archone.messaging.events.EventMessageHeaders;
import com.flowzati.archone.messaging.jdbc.JdbcStatementExecutor;
import com.flowzati.archone.messaging.jdbc.MessagingSchema;
import com.flowzati.archone.messaging.jdbc.MessagingSqlDialect;
import com.flowzati.archone.messaging.jdbc.MessagingTableNames;
import com.flowzati.archone.messaging.jdbc.MessagingTransactionTemplate;
import com.flowzati.archone.messaging.producer.common.MessageProducerImpl;
import com.flowzati.archone.messaging.producer.common.RandomUuidMessageIdGenerator;
import com.flowzati.archone.messaging.producer.jdbc.HeaderMappedOutboxMessageMapper;
import com.flowzati.archone.messaging.producer.jdbc.JacksonMessageHeadersCodec;
import com.flowzati.archone.messaging.producer.jdbc.JdbcOutboxMessageProducerImplementation;
import com.flowzati.archone.messaging.producer.jdbc.MessageHeadersCodec;
import com.flowzati.archone.messaging.producer.jdbc.OutboxMessageMapper;
import com.flowzati.archone.messaging.producer.jdbc.OutboxPhysicalHeaders;
import com.flowzati.archone.messaging.spring.producer.jdbc.CallerTransactionRequiredMessageProducerImplementation;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.ObjectMapper;

/** Composes the caller-transaction-required JDBC Outbox producer. */
@AutoConfiguration(after = {MessagingCoreAutoConfiguration.class, MessagingJdbcAutoConfiguration.class})
@ConditionalOnClass({
    JdbcOutboxMessageProducerImplementation.class,
    CallerTransactionRequiredMessageProducerImplementation.class,
    ObjectMapper.class
})
@ConditionalOnBean({JdbcStatementExecutor.class, MessagingTransactionTemplate.class})
@ConditionalOnProperty(
    prefix = "archone.messaging.producer.jdbc",
    name = "enabled",
    matchIfMissing = true
)
public class MessagingProducerJdbcAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  MessageHeadersCodec messageHeadersCodec(ObjectProvider<ObjectMapper> objectMappers) {
    ObjectMapper objectMapper = objectMappers.getIfUnique(ObjectMapper::new);
    return new JacksonMessageHeadersCodec(
        objectMapper,
        OutboxPhysicalHeaders.ALL,
        JacksonMessageHeadersCodec.DEFAULT_MAX_HEADER_COUNT,
        JacksonMessageHeadersCodec.DEFAULT_MAX_ENCODED_BYTES);
  }

  @Bean
  @ConditionalOnMissingBean
  OutboxMessageMapper outboxMessageMapper() {
    return HeaderMappedOutboxMessageMapper.withAggregateHeaders(
        EventMessageHeaders.EVENT_AGGREGATE_TYPE,
        EventMessageHeaders.EVENT_AGGREGATE_ID);
  }

  @Bean
  @ConditionalOnMissingBean
  MessageIdGenerator messageIdGenerator() {
    return new RandomUuidMessageIdGenerator();
  }

  @Bean
  @ConditionalOnMissingBean
  JdbcOutboxMessageProducerImplementation jdbcOutboxMessageProducerImplementation(
      JdbcStatementExecutor statementExecutor,
      MessagingSqlDialect dialect,
      MessagingSchema schema,
      MessagingTableNames tableNames,
      OutboxMessageMapper messageMapper,
      MessageHeadersCodec headersCodec
  ) {
    return new JdbcOutboxMessageProducerImplementation(
        statementExecutor, dialect, schema, tableNames, messageMapper, headersCodec);
  }

  @Bean
  @ConditionalOnMissingBean
  CallerTransactionRequiredMessageProducerImplementation
      callerTransactionRequiredMessageProducerImplementation(
          MessagingTransactionTemplate transactionTemplate,
          JdbcOutboxMessageProducerImplementation delegate
      ) {
    return new CallerTransactionRequiredMessageProducerImplementation(
        transactionTemplate, delegate);
  }

  @Bean
  @ConditionalOnMissingBean
  MessageProducer messageProducer(
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
