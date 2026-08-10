package com.flowzati.archone.messaging.autoconfigure;

import com.flowzati.archone.messaging.consumer.observation.ConsumerObservationDecorator;
import com.flowzati.archone.messaging.observation.ConsumerMessageObservationConvention;
import com.flowzati.archone.messaging.observation.ProducerMessageObservationConvention;
import com.flowzati.archone.messaging.producer.observation.ProducerObservationInterceptor;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/**
 * Adds messaging observation adapters only when an application supplies Micrometer observation.
 * Exporter selection remains entirely owned by the application.
 */
@AutoConfiguration(
    before = {
        ArchoneMessagingAutoConfiguration.class,
        ArchoneMessagingJdbcProducerAutoConfiguration.class
    },
    afterName =
        "org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration"
)
@ConditionalOnClass(ObservationRegistry.class)
@ConditionalOnBean(ObservationRegistry.class)
@ConditionalOnProperty(
    prefix = "archone.messaging.observation",
    name = "enabled",
    matchIfMissing = true
)
public class ArchoneMessagingObservationAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  @Order(Ordered.LOWEST_PRECEDENCE)
  @ConditionalOnProperty(
      prefix = "archone.messaging.observation.producer",
      name = "enabled",
      matchIfMissing = true
  )
  ProducerObservationInterceptor producerObservationInterceptor(
      ObservationRegistry observationRegistry,
      ObjectProvider<ProducerMessageObservationConvention> conventions
  ) {
    ProducerMessageObservationConvention convention = conventions.getIfAvailable();
    return convention == null
        ? new ProducerObservationInterceptor(observationRegistry)
        : new ProducerObservationInterceptor(observationRegistry, convention);
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnProperty(
      prefix = "archone.messaging.observation.consumer",
      name = "enabled",
      matchIfMissing = true
  )
  ConsumerObservationDecorator consumerObservationDecorator(
      ObservationRegistry observationRegistry,
      ObjectProvider<ConsumerMessageObservationConvention> conventions
  ) {
    ConsumerMessageObservationConvention convention = conventions.getIfAvailable();
    return convention == null
        ? new ConsumerObservationDecorator(observationRegistry)
        : new ConsumerObservationDecorator(observationRegistry, convention);
  }
}
