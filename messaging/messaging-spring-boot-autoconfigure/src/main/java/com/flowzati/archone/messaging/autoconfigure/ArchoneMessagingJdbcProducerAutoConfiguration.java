package com.flowzati.archone.messaging.autoconfigure;

import com.flowzati.archone.messaging.api.ChannelMapping;
import com.flowzati.archone.messaging.api.IdentityChannelMapping;
import com.flowzati.archone.messaging.api.MessageIdGenerator;
import com.flowzati.archone.messaging.api.MessageProducer;
import com.flowzati.archone.messaging.events.EventMessageHeaders;
import com.flowzati.archone.messaging.jdbc.MessagingSchema;
import com.flowzati.archone.messaging.jdbc.MessagingSqlDialect;
import com.flowzati.archone.messaging.jdbc.MessagingTableNames;
import com.flowzati.archone.messaging.jdbc.PostgresMessagingSqlDialect;
import com.flowzati.archone.messaging.producer.common.RandomUuidMessageIdGenerator;
import com.flowzati.archone.messaging.producer.jdbc.HeaderMappedOutboxMessageMapper;
import com.flowzati.archone.messaging.producer.jdbc.OutboxMessageMapper;
import com.flowzati.archone.messaging.spring.producer.jdbc.SpringJdbcMessageProducerConfiguration;
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
 * Supplies Integration Event defaults around the generic Spring JDBC producer composition.
 * The resulting producer appends synchronously to Outbox in the caller's active transaction;
 * Debezium remains the only Kafka producer path.
 */
@AutoConfiguration(
    after = JdbcTemplateAutoConfiguration.class,
    afterName = {
        "com.flowzati.archone.messaging.autoconfigure.ArchoneMessagingAutoConfiguration",
        "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
        "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration"
    }
)
@ConditionalOnClass(JdbcOperations.class)
@ConditionalOnBean({JdbcOperations.class, PlatformTransactionManager.class})
@ConditionalOnMissingBean(MessageProducer.class)
@ConditionalOnProperty(
    prefix = "archone.messaging.producer.jdbc",
    name = "enabled",
    matchIfMissing = true
)
@Import(SpringJdbcMessageProducerConfiguration.class)
public class ArchoneMessagingJdbcProducerAutoConfiguration {

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

  @Bean
  @ConditionalOnMissingBean
  OutboxMessageMapper outboxMessageMapper() {
    return HeaderMappedOutboxMessageMapper.withAggregateHeaders(
        EventMessageHeaders.EVENT_AGGREGATE_TYPE,
        EventMessageHeaders.EVENT_AGGREGATE_ID);
  }

  @Bean
  @ConditionalOnMissingBean
  ChannelMapping channelMapping() {
    return IdentityChannelMapping.INSTANCE;
  }

  @Bean
  @ConditionalOnMissingBean
  MessageIdGenerator messageIdGenerator() {
    return new RandomUuidMessageIdGenerator();
  }

  @Bean
  @ConditionalOnMissingBean
  Clock messagingClock() {
    return Clock.systemUTC();
  }

}
