package com.flowzati.archone.messaging.autoconfigure;

import com.flowzati.archone.messaging.api.MessageProducer;
import com.flowzati.archone.messaging.events.DefaultIntegrationEventPublisher;
import com.flowzati.archone.messaging.events.IntegrationEventPublisher;
import com.flowzati.archone.messaging.events.IntegrationEventSerializer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/** Adds the typed Integration Event facade after a generic producer is present. */
@AutoConfiguration(after = MessagingProducerJdbcAutoConfiguration.class)
@ConditionalOnClass(DefaultIntegrationEventPublisher.class)
@ConditionalOnBean({MessageProducer.class, IntegrationEventSerializer.class})
@ConditionalOnProperty(
    prefix = "archone.messaging.events.publisher",
    name = "enabled",
    matchIfMissing = true
)
public class MessagingIntegrationEventPublisherAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  IntegrationEventPublisher integrationEventPublisher(
      MessageProducer messageProducer,
      IntegrationEventSerializer serializer
  ) {
    return new DefaultIntegrationEventPublisher(messageProducer, serializer);
  }
}
