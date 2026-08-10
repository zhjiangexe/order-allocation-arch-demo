package com.flowzati.archone.messaging.autoconfigure;

import com.flowzati.archone.messaging.api.MessageConsumer;
import com.flowzati.archone.messaging.events.IntegrationEventDeserializer;
import com.flowzati.archone.messaging.events.IntegrationEventDispatcherFactory;
import com.flowzati.archone.messaging.events.IntegrationEventNameMapping;
import com.flowzati.archone.messaging.events.UnhandledIntegrationEventObserver;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
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

  private static final Log LOGGER = LogFactory.getLog(
      MessagingIntegrationEventDispatcherAutoConfiguration.class);

  /** Default reason-rich diagnostic; generic consumer observation records the outcome metric. */
  @Bean
  @ConditionalOnMissingBean
  UnhandledIntegrationEventObserver unhandledIntegrationEventObserver() {
    return event -> {
      if (LOGGER.isDebugEnabled()) {
        LOGGER.debug("Ignored unhandled Integration Event: destination="
            + event.destination() + ", eventType=" + event.eventType()
            + ", contractVersion=" + event.contractVersion()
            + ", reason=" + event.reason() + ", messageId=" + event.message().id());
      }
    };
  }

  @Bean
  @ConditionalOnMissingBean
  IntegrationEventDispatcherFactory integrationEventDispatcherFactory(
      MessageConsumer messageConsumer,
      IntegrationEventDeserializer deserializer,
      IntegrationEventNameMapping nameMapping,
      UnhandledIntegrationEventObserver unhandledEventObserver
  ) {
    return new IntegrationEventDispatcherFactory(
        messageConsumer, deserializer, nameMapping, unhandledEventObserver);
  }
}
