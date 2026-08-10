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
import com.flowzati.archone.messaging.kafka.KafkaMessageMapper;
import com.flowzati.archone.messaging.spring.consumer.kafka.KafkaConsumerFailureObserver;
import com.flowzati.archone.messaging.spring.consumer.kafka.KafkaConsumerFailurePolicyResolver;
import com.flowzati.archone.messaging.spring.consumer.kafka.KafkaConsumerObservationMetadataResolver;
import com.flowzati.archone.messaging.spring.consumer.kafka.KafkaDeadLetterErrorHandlerFactory;
import com.flowzati.archone.messaging.spring.consumer.kafka.JacksonKafkaMessageHeadersDecoder;
import com.flowzati.archone.messaging.spring.consumer.kafka.KafkaSubscriptionErrorHandlerFactory;
import com.flowzati.archone.messaging.spring.consumer.kafka.KafkaSubscriptionPolicy;
import com.flowzati.archone.messaging.spring.consumer.kafka.KafkaSubscriptionPolicyResolver;
import com.flowzati.archone.messaging.spring.consumer.kafka.MicrometerKafkaConsumerFailureObserver;
import com.flowzati.archone.messaging.spring.consumer.kafka.SpringKafkaMessageConsumerImplementation;
import io.micrometer.observation.ObservationRegistry;
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
import org.springframework.kafka.core.KafkaOperations;

/** Programmatic Spring Kafka consumer runtime composition. */
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
    MessagingConsumerProperties.class
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
  KafkaSubscriptionPolicyResolver kafkaSubscriptionPolicyResolver() {
    return KafkaSubscriptionPolicyResolver.fixed(KafkaSubscriptionPolicy.defaults());
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnBean({KafkaConsumerFailurePolicyResolver.class, KafkaOperations.class})
  KafkaSubscriptionErrorHandlerFactory kafkaSubscriptionErrorHandlerFactory(
      KafkaOperations<Object, Object> kafkaOperations,
      KafkaConsumerFailurePolicyResolver failurePolicyResolver,
      ObjectProvider<ObservationRegistry> observationRegistries
  ) {
    ObservationRegistry observationRegistry = observationRegistries.getIfAvailable();
    return KafkaDeadLetterErrorHandlerFactory.perSubscription(
        kafkaOperations,
        failurePolicyResolver,
        subscription -> observationRegistry == null
            ? KafkaConsumerFailureObserver.none()
            : new MicrometerKafkaConsumerFailureObserver(
                observationRegistry,
                KafkaConsumerObservationMetadataResolver.forSubscription(subscription)));
  }

  @Bean(destroyMethod = "close")
  @ConditionalOnMissingBean(MessageConsumerImplementation.class)
  SpringKafkaMessageConsumerImplementation kafkaMessageConsumerImplementation(
      ConcurrentKafkaListenerContainerFactory<String, String> containerFactory,
      KafkaMessageMapper messageMapper,
      KafkaSubscriptionPolicyResolver policyResolver,
      ObjectProvider<KafkaSubscriptionErrorHandlerFactory> errorHandlerFactories
  ) {
    return new SpringKafkaMessageConsumerImplementation(
        containerFactory,
        messageMapper,
        policyResolver,
        errorHandlerFactories.getIfAvailable(KafkaSubscriptionErrorHandlerFactory::none));
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

}
