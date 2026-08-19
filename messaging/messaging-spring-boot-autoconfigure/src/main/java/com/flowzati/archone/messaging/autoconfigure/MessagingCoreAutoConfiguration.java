package com.flowzati.archone.messaging.autoconfigure;

import com.flowzati.archone.messaging.api.ChannelMapping;
import com.flowzati.archone.messaging.api.IdentityChannelMapping;
import com.flowzati.archone.messaging.api.MapBasedChannelMapping;
import com.flowzati.archone.messaging.events.IntegrationEventDeserializer;
import com.flowzati.archone.messaging.events.IntegrationEventSerializer;
import com.flowzati.archone.messaging.events.JacksonIntegrationEventSerde;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.DateTimeFeature;

/** Framework-neutral defaults shared by narrow producer and consumer starters. */
@AutoConfiguration
@ConditionalOnClass(ChannelMapping.class)
@ConditionalOnProperty(prefix = "archone.messaging.core", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(MessagingChannelProperties.class)
public class MessagingCoreAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    ChannelMapping channelMapping(MessagingChannelProperties properties) {
        return properties.getMappings().isEmpty()
                ? IdentityChannelMapping.INSTANCE
                : new MapBasedChannelMapping(properties.getMappings());
    }

    @Bean
    @ConditionalOnMissingBean
    Clock messagingClock() {
        return Clock.systemUTC();
    }

    /** Isolates optional typed-event and Jackson classes from core class loading. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({JacksonIntegrationEventSerde.class, ObjectMapper.class})
    static class IntegrationEventSerdeConfiguration {

        @Bean
        @ConditionalOnMissingBean({IntegrationEventSerializer.class, IntegrationEventDeserializer.class})
        JacksonIntegrationEventSerde integrationEventSerde(ObjectProvider<ObjectMapper> objectMappers) {
            ObjectMapper baseObjectMapper = objectMappers.getIfUnique(ObjectMapper::new);
            ObjectMapper eventObjectMapper = baseObjectMapper
                    .rebuild()
                    .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
                    .build();
            return new JacksonIntegrationEventSerde(eventObjectMapper);
        }
    }
}
