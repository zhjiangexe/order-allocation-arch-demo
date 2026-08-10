package com.flowzati.archone.messaging.autoconfigure;

import com.flowzati.archone.messaging.consumer.observation.ConsumerObservationDecorator;
import com.flowzati.archone.messaging.observation.ConsumerMessageObservationConvention;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/** Consumer observation adapter kept out of producer-only dependency graphs. */
@AutoConfiguration(after = MessagingObservationAutoConfiguration.class)
@ConditionalOnClass({ObservationRegistry.class, ConsumerObservationDecorator.class})
@ConditionalOnBean(ObservationRegistry.class)
@ConditionalOnProperty(
    prefix = "archone.messaging.observation",
    name = "enabled",
    matchIfMissing = true
)
@ConditionalOnProperty(
    prefix = "archone.messaging.observation.consumer",
    name = "enabled",
    matchIfMissing = true
)
public class MessagingConsumerObservationAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
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
