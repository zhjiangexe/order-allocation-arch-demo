package com.flowzati.archone.messaging.autoconfigure;

import com.flowzati.archone.messaging.api.MessageConsumer;
import com.flowzati.archone.messaging.events.IntegrationEventDeserializer;
import com.flowzati.archone.messaging.events.IntegrationEventDispatcherFactory;
import com.flowzati.archone.messaging.events.IntegrationEventNameMapping;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/** Typed dispatcher factory activated only after an application declares stable event mappings. */
@AutoConfiguration(after = MessagingKafkaConsumerAutoConfiguration.class)
@ConditionalOnClass(IntegrationEventDispatcherFactory.class)
@ConditionalOnBean({
    MessageConsumer.class,
    IntegrationEventDeserializer.class,
    IntegrationEventNameMapping.class
})
@ConditionalOnProperty(
    prefix = "archone.messaging.events.dispatcher",
    name = "enabled",
    matchIfMissing = true
)
public class MessagingIntegrationEventDispatcherAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  IntegrationEventDispatcherFactory integrationEventDispatcherFactory(
      MessageConsumer messageConsumer,
      IntegrationEventDeserializer deserializer,
      IntegrationEventNameMapping nameMapping
  ) {
    return new IntegrationEventDispatcherFactory(messageConsumer, deserializer, nameMapping);
  }
}
