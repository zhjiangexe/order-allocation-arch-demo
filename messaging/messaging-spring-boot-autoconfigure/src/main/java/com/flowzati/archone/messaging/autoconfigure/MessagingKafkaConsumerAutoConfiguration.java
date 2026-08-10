package com.flowzati.archone.messaging.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowzati.archone.messaging.api.ChannelMapping;
import com.flowzati.archone.messaging.api.ConsumerGroupMapping;
import com.flowzati.archone.messaging.api.IdentityConsumerGroupMapping;
import com.flowzati.archone.messaging.api.MapBasedConsumerGroupMapping;
import com.flowzati.archone.messaging.api.MessageConsumer;
import com.flowzati.archone.messaging.api.MessageHeadersDecoder;
import com.flowzati.archone.messaging.consumer.common.MessageConsumerImpl;
import com.flowzati.archone.messaging.consumer.common.MessageConsumerImplementation;
import com.flowzati.archone.messaging.consumer.common.MessageHandlerDecorator;
import com.flowzati.archone.messaging.events.IntegrationEventDeserializer;
import com.flowzati.archone.messaging.events.IntegrationEventHandler;
import com.flowzati.archone.messaging.kafka.KafkaIntegrationEventDispatcher;
import com.flowzati.archone.messaging.kafka.KafkaMessageMapper;
import com.flowzati.archone.messaging.spring.consumer.kafka.JacksonKafkaMessageHeadersDecoder;
import com.flowzati.archone.messaging.spring.consumer.kafka.KafkaSubscriptionPolicy;
import com.flowzati.archone.messaging.spring.consumer.kafka.KafkaSubscriptionPolicyResolver;
import com.flowzati.archone.messaging.spring.consumer.kafka.SpringKafkaMessageConsumerImplementation;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;

/** Programmatic Spring Kafka consumer runtime and temporary legacy bridge composition. */
@AutoConfiguration(after = {
    MessagingCoreAutoConfiguration.class,
    MessagingConsumerJdbcAutoConfiguration.class,
    MessagingProducerJdbcAutoConfiguration.class
}, afterName = "org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration")
@ConditionalOnClass({
    SpringKafkaMessageConsumerImplementation.class,
    ConcurrentKafkaListenerContainerFactory.class,
    KafkaMessageMapper.class
})
@ConditionalOnBean(ConcurrentKafkaListenerContainerFactory.class)
@ConditionalOnProperty(
    prefix = "archone.messaging.consumer.kafka",
    name = "enabled",
    matchIfMissing = true
)
@EnableConfigurationProperties({
    MessagingConsumerProperties.class,
    MessagingKafkaConsumerProperties.class
})
public class MessagingKafkaConsumerAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  ConsumerGroupMapping consumerGroupMapping(MessagingConsumerProperties properties) {
    return properties.getGroups().isEmpty()
        ? IdentityConsumerGroupMapping.INSTANCE
        : new MapBasedConsumerGroupMapping(properties.getGroups());
  }

  @Bean
  @ConditionalOnMissingBean
  MessageHeadersDecoder kafkaMessageHeadersDecoder(
      ObjectProvider<ObjectMapper> objectMappers
  ) {
    ObjectMapper objectMapper = objectMappers.getIfUnique(ObjectMapper::new);
    return new JacksonKafkaMessageHeadersDecoder(objectMapper);
  }

  @Bean
  @ConditionalOnMissingBean
  KafkaMessageMapper kafkaMessageMapper(MessageHeadersDecoder headersDecoder) {
    return new KafkaMessageMapper(headersDecoder);
  }

  @Bean
  @ConditionalOnMissingBean
  KafkaSubscriptionPolicyResolver kafkaSubscriptionPolicyResolver(
      MessagingKafkaConsumerProperties properties
  ) {
    KafkaSubscriptionPolicy policy = KafkaSubscriptionPolicy.builder()
        .concurrency(properties.getConcurrency())
        .ackMode(properties.getAckMode())
        .missingTopicsFatal(properties.isMissingTopicsFatal())
        .observationEnabled(properties.isObservationEnabled())
        .shutdownTimeout(properties.getShutdownTimeout())
        .build();
    return KafkaSubscriptionPolicyResolver.fixed(policy);
  }

  @Bean(destroyMethod = "close")
  @ConditionalOnMissingBean(MessageConsumerImplementation.class)
  SpringKafkaMessageConsumerImplementation kafkaMessageConsumerImplementation(
      ConcurrentKafkaListenerContainerFactory<String, String> containerFactory,
      KafkaMessageMapper messageMapper,
      KafkaSubscriptionPolicyResolver policyResolver
  ) {
    return new SpringKafkaMessageConsumerImplementation(
        containerFactory, messageMapper, policyResolver);
  }

  @Bean
  @ConditionalOnMissingBean
  MessageConsumer messageConsumer(
      MessageConsumerImplementation implementation,
      ChannelMapping channelMapping,
      ConsumerGroupMapping consumerGroupMapping,
      List<MessageHandlerDecorator> decorators
  ) {
    return new MessageConsumerImpl(
        implementation, channelMapping, consumerGroupMapping, decorators);
  }

  /** Temporary global-handler bridge retained until Gate I migrates application subscribers. */
  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnClass(KafkaIntegrationEventDispatcher.class)
  KafkaIntegrationEventDispatcher kafkaIntegrationEventDispatcher(
      KafkaMessageMapper messageMapper,
      IntegrationEventDeserializer deserializer,
      ObjectProvider<IntegrationEventHandler<?>> handlers,
      ObjectProvider<MessageHandlerDecorator> decorators
  ) {
    return new KafkaIntegrationEventDispatcher(
        messageMapper,
        deserializer,
        handlers.orderedStream().toList(),
        decorators.orderedStream().toList());
  }
}
