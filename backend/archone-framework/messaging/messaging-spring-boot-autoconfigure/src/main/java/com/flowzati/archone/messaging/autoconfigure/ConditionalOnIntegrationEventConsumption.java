package com.flowzati.archone.messaging.autoconfigure;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.autoconfigure.condition.AllNestedConditions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.ConfigurationCondition.ConfigurationPhase;

/** Single application-facing capability condition for typed Integration Event subscriptions. */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Conditional(OnIntegrationEventConsumptionCondition.class)
public @interface ConditionalOnIntegrationEventConsumption {}

final class OnIntegrationEventConsumptionCondition extends AllNestedConditions {

    OnIntegrationEventConsumptionCondition() {
        super(ConfigurationPhase.PARSE_CONFIGURATION);
    }

    @ConditionalOnProperty(
            prefix = "archone.messaging.core",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    static final class CoreEnabled {}

    @ConditionalOnProperty(
            prefix = "archone.messaging.consumer.kafka",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    static final class KafkaConsumerEnabled {}

    @ConditionalOnProperty(
            prefix = "archone.messaging.events.dispatcher",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    static final class IntegrationEventDispatcherEnabled {}
}
