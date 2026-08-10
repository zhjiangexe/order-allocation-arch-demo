package com.flowzati.archone.messaging.autoconfigure;

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

/** Producer observation adapter kept out of consumer-only dependency graphs. */
@AutoConfiguration(after = MessagingObservationAutoConfiguration.class)
@ConditionalOnClass({ObservationRegistry.class, ProducerObservationInterceptor.class})
@ConditionalOnBean(ObservationRegistry.class)
@ConditionalOnProperty(
    prefix = "archone.messaging.observation",
    name = "enabled",
    matchIfMissing = true
)
@ConditionalOnProperty(
    prefix = "archone.messaging.observation.producer",
    name = "enabled",
    matchIfMissing = true
)
public class MessagingProducerObservationAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  @Order(Ordered.LOWEST_PRECEDENCE)
  ProducerObservationInterceptor producerObservationInterceptor(
      ObservationRegistry observationRegistry,
      ObjectProvider<ProducerMessageObservationConvention> conventions
  ) {
    ProducerMessageObservationConvention convention = conventions.getIfAvailable();
    return convention == null
        ? new ProducerObservationInterceptor(observationRegistry)
        : new ProducerObservationInterceptor(observationRegistry, convention);
  }
}
