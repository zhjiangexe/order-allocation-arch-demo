package com.flowzati.archone.messaging.autoconfigure;

import com.flowzati.archone.messaging.observation.ConsumerMessageObservationConvention;
import com.flowzati.archone.messaging.observation.DefaultConsumerMessageObservationConvention;
import com.flowzati.archone.messaging.observation.DefaultProducerMessageObservationConvention;
import com.flowzati.archone.messaging.observation.ProducerMessageObservationConvention;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/** Shared observation conventions; exporter selection remains application-owned. */
@AutoConfiguration(
        afterName = "org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration")
@ConditionalOnClass(ObservationRegistry.class)
@ConditionalOnBean(ObservationRegistry.class)
@ConditionalOnProperty(prefix = "archone.messaging.observation", name = "enabled", matchIfMissing = true)
public class MessagingObservationAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    ProducerMessageObservationConvention producerMessageObservationConvention() {
        return new DefaultProducerMessageObservationConvention();
    }

    @Bean
    @ConditionalOnMissingBean
    ConsumerMessageObservationConvention consumerMessageObservationConvention() {
        return new DefaultConsumerMessageObservationConvention();
    }
}
