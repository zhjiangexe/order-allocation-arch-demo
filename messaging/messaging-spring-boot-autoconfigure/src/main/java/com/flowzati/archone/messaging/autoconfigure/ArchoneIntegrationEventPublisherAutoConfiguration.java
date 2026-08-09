package com.flowzati.archone.messaging.autoconfigure;

import com.flowzati.archone.messaging.api.MessageProducer;
import com.flowzati.archone.messaging.events.DefaultIntegrationEventPublisher;
import com.flowzati.archone.messaging.events.IntegrationEventPublisher;
import com.flowzati.archone.messaging.events.IntegrationEventSerializer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/** Adds the typed Integration Event facade after an application or library producer is present. */
@AutoConfiguration(after = {
    ArchoneMessagingJdbcProducerAutoConfiguration.class,
    ArchoneMessagingJpaAutoConfiguration.class
})
@ConditionalOnBean(MessageProducer.class)
public class ArchoneIntegrationEventPublisherAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  IntegrationEventPublisher integrationEventPublisher(
      MessageProducer messageProducer,
      IntegrationEventSerializer serializer
  ) {
    return new DefaultIntegrationEventPublisher(messageProducer, serializer);
  }
}
