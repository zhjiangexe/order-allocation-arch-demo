package com.flowzati.archone.messaging.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.flowzati.archone.messaging.MessagingPackage;
import com.flowzati.archone.messaging.events.IntegrationEventDeserializer;
import com.flowzati.archone.messaging.events.IntegrationEventHandler;
import com.flowzati.archone.messaging.events.IntegrationEventSerializer;
import com.flowzati.archone.messaging.events.JacksonIntegrationEventSerde;
import com.flowzati.archone.messaging.kafka.KafkaIntegrationEventDispatcher;
import com.flowzati.archone.messaging.producer.jdbc.JacksonMessageHeadersCodec;
import com.flowzati.archone.messaging.producer.jdbc.MessageHeadersCodec;
import com.flowzati.archone.messaging.producer.jdbc.OutboxPhysicalHeaders;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Composes the reusable transactional-messaging building blocks.
 *
 * <p>The application still owns explicit {@code @KafkaListener} methods and their retry, DLT,
 * concurrency, and ordering policies. This auto-configuration only provides serialization,
 * dispatch, Inbox, and Outbox infrastructure.
 */
@AutoConfiguration(before = DataJpaRepositoriesAutoConfiguration.class)
public class ArchoneMessagingAutoConfiguration {

  /**
   * Registers the library package for applications outside {@code com.flowzati.archone}.
   * Applications whose root already covers it must set
   * {@code archone.messaging.jpa.register-package=false} to avoid a duplicate repository scan.
   */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnProperty(
      prefix = "archone.messaging.jpa",
      name = "register-package",
      matchIfMissing = true
  )
  @AutoConfigurationPackage(basePackageClasses = MessagingPackage.class)
  static class MessagingPackageConfiguration {
  }

  @Bean
  @ConditionalOnMissingBean({IntegrationEventSerializer.class, IntegrationEventDeserializer.class})
  JacksonIntegrationEventSerde integrationEventSerde(
      ObjectProvider<ObjectMapper> objectMappers
  ) {
    ObjectMapper baseObjectMapper = objectMappers.getIfUnique(ObjectMapper::new);
    ObjectMapper eventObjectMapper = baseObjectMapper.copy()
        .findAndRegisterModules()
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    return new JacksonIntegrationEventSerde(eventObjectMapper);
  }

  @Bean
  @ConditionalOnMissingBean
  MessageHeadersCodec messageHeadersCodec(ObjectProvider<ObjectMapper> objectMappers) {
    ObjectMapper objectMapper = objectMappers.getIfUnique(ObjectMapper::new);
    return new JacksonMessageHeadersCodec(
        objectMapper,
        OutboxPhysicalHeaders.ALL,
        JacksonMessageHeadersCodec.DEFAULT_MAX_HEADER_COUNT,
        JacksonMessageHeadersCodec.DEFAULT_MAX_ENCODED_BYTES);
  }

  @Bean
  @ConditionalOnMissingBean
  KafkaIntegrationEventDispatcher kafkaIntegrationEventDispatcher(
      IntegrationEventDeserializer deserializer,
      ObjectProvider<IntegrationEventHandler<?>> handlers,
      MessageHeadersCodec headersCodec
  ) {
    List<IntegrationEventHandler<?>> registeredHandlers = handlers.orderedStream().toList();
    return new KafkaIntegrationEventDispatcher(
        deserializer, registeredHandlers, headersCodec);
  }

}
