package com.flowzati.archone.messaging.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.flowzati.archone.messaging.api.ChannelMapping;
import com.flowzati.archone.messaging.api.ConsumerGroupMapping;
import com.flowzati.archone.messaging.api.MessageBuilder;
import com.flowzati.archone.messaging.api.MessageConsumer;
import com.flowzati.archone.messaging.api.MessageProducer;
import com.flowzati.archone.messaging.consumer.common.DuplicateMessageDetector;
import com.flowzati.archone.messaging.consumer.common.MessageFailureCategory;
import com.flowzati.archone.messaging.consumer.common.MessageFailureClassification;
import com.flowzati.archone.messaging.consumer.common.ResolvedMessageSubscription;
import com.flowzati.archone.messaging.consumer.jdbc.TransactionalIdempotencyMessageHandlerDecorator;
import com.flowzati.archone.messaging.events.IntegrationEventDeserializer;
import com.flowzati.archone.messaging.events.IntegrationEventDispatcherFactory;
import com.flowzati.archone.messaging.events.IntegrationEventPublisher;
import com.flowzati.archone.messaging.events.IntegrationEventSerializer;
import com.flowzati.archone.messaging.events.MapBasedIntegrationEventNameMapping;
import com.flowzati.archone.messaging.events.UnhandledIntegrationEventObserver;
import com.flowzati.archone.messaging.spring.consumer.kafka.KafkaConsumerFailurePolicy;
import com.flowzati.archone.messaging.spring.consumer.kafka.KafkaConsumerFailurePolicyResolver;
import com.flowzati.archone.messaging.spring.consumer.kafka.KafkaSubscriptionErrorHandlerFactory;
import com.flowzati.archone.messaging.spring.consumer.kafka.KafkaSubscriptionPolicyResolver;
import com.flowzati.archone.messaging.spring.consumer.kafka.SpringKafkaMessageConsumerImplementation;
import com.flowzati.archone.messaging.spring.flyway.MessagingFlywayFactory;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.util.backoff.FixedBackOff;
import tools.jackson.databind.ObjectMapper;

class MessagingAutoConfigurationTest {

    private final ApplicationContextRunner coreRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MessagingCoreAutoConfiguration.class))
            .withBean(ObjectMapper.class, ObjectMapper::new);

    @Test
    void coreProvidesSerdeIdentityChannelMappingAndApplicationOverride() {
        coreRunner.run(context -> {
            assertThat(context).hasSingleBean(IntegrationEventSerializer.class);
            assertThat(context).hasSingleBean(IntegrationEventDeserializer.class);
            assertThat(context).hasSingleBean(ChannelMapping.class);
            assertThat(context.getBean(ChannelMapping.class).transform("orders"))
                    .isEqualTo("orders");
            assertThat(context).doesNotHaveBean(MessageProducer.class);
            assertThat(context).doesNotHaveBean(MessageConsumer.class);
        });

        ChannelMapping custom = logical -> "custom-" + logical;
        coreRunner
                .withBean(ChannelMapping.class, () -> custom)
                .run(context ->
                        assertThat(context.getBean(ChannelMapping.class)).isSameAs(custom));
    }

    @Test
    void bindsAndValidatesChannelMappingsAtStartup() {
        coreRunner
                .withPropertyValues(
                        "archone.messaging.channels.mappings.orders=order-events",
                        "archone.messaging.channels.mappings.stock=stock-events")
                .run(context -> {
                    assertThat(context.getBean(ChannelMapping.class).transform("orders"))
                            .isEqualTo("order-events");
                    assertThat(context.getBean(ChannelMapping.class).transform("unmapped"))
                            .isEqualTo("unmapped");
                });

        coreRunner
                .withPropertyValues(
                        "archone.messaging.channels.mappings.orders=shared-events",
                        "archone.messaging.channels.mappings.stock=shared-events")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void composesJdbcProducerAndConsumerWithoutReplacingApplicationBeans() {
        jdbcRunner().run(context -> {
            assertThat(context).hasSingleBean(MessageProducer.class);
            assertThat(context).hasSingleBean(IntegrationEventPublisher.class);
            assertThat(context).hasSingleBean(DuplicateMessageDetector.class);
            assertThat(context).hasSingleBean(TransactionalIdempotencyMessageHandlerDecorator.class);
        });

        MessageProducer customProducer = mock(MessageProducer.class);
        jdbcRunner()
                .withBean(MessageProducer.class, () -> customProducer)
                .run(context ->
                        assertThat(context.getBean(MessageProducer.class)).isSameAs(customProducer));
    }

    @Test
    void producerAndConsumerJdbcCanBeDisabledIndependently() {
        jdbcRunner()
                .withPropertyValues("archone.messaging.producer.jdbc.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(MessageProducer.class);
                    assertThat(context).hasSingleBean(DuplicateMessageDetector.class);
                });

        jdbcRunner()
                .withPropertyValues("archone.messaging.consumer.jdbc.enabled=false")
                .run(context -> {
                    assertThat(context).hasSingleBean(MessageProducer.class);
                    assertThat(context).doesNotHaveBean(DuplicateMessageDetector.class);
                });
    }

    @Test
    void rejectsOutboxProductionWithoutAnActiveCallerTransaction() {
        jdbcRunner()
                .run(context -> assertThatThrownBy(() -> context.getBean(MessageProducer.class)
                                .send(
                                        "order-events",
                                        MessageBuilder.withPayload("{}")
                                                .withType("OrderPlaced.v1")
                                                .withPartitionId("order-1")
                                                .build()))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessage("No active caller transaction for transactional message production"));
    }

    @Test
    void composesProgrammaticKafkaConsumerAndSeparateGroupMapping() {
        kafkaRunner()
                .withPropertyValues("archone.messaging.consumer.groups.allocation=allocation-v2")
                .run(context -> {
                    assertThat(context).hasSingleBean(SpringKafkaMessageConsumerImplementation.class);
                    assertThat(context).hasSingleBean(MessageConsumer.class);
                    assertThat(context).hasSingleBean(KafkaSubscriptionPolicyResolver.class);
                    assertThat(context.getBean(ConsumerGroupMapping.class).transform("allocation"))
                            .isEqualTo("allocation-v2");
                    assertThat(context.getBean(ConsumerGroupMapping.class).transform("ordering"))
                            .isEqualTo("ordering");
                    var policy = context.getBean(KafkaSubscriptionPolicyResolver.class)
                            .resolve(new ResolvedMessageSubscription(
                                    "allocation", "allocation-v2", Map.of("order-events", "orders")));
                    assertThat(policy.concurrencyOverride()).isEmpty();
                    assertThat(policy.observationEnabledOverride()).isEmpty();
                    assertThat(policy.autoStartupOverride()).isEmpty();
                    assertThat(context).doesNotHaveBean(IntegrationEventDispatcherFactory.class);
                });
    }

    @Test
    @SuppressWarnings("unchecked")
    void standardSpringListenerPropertiesConfigureTheSharedKafkaFactory() {
        bootKafkaRunner()
                .withPropertyValues(
                        "spring.kafka.listener.concurrency=4",
                        "spring.kafka.listener.ack-mode=record",
                        "spring.kafka.listener.missing-topics-fatal=false",
                        "spring.kafka.listener.observation-enabled=true",
                        "spring.kafka.listener.auto-startup=false")
                .run(context -> {
                    ConcurrentKafkaListenerContainerFactory<Object, Object> factory =
                            (ConcurrentKafkaListenerContainerFactory<Object, Object>)
                                    context.getBean(ConcurrentKafkaListenerContainerFactory.class);
                    ConcurrentMessageListenerContainer<Object, Object> container =
                            factory.createContainer("order-events");

                    assertThat(container.getConcurrency()).isEqualTo(4);
                    assertThat(container.getContainerProperties().getAckMode())
                            .isEqualTo(ContainerProperties.AckMode.RECORD);
                    assertThat(container.getContainerProperties().isMissingTopicsFatal())
                            .isFalse();
                    assertThat(container.getContainerProperties().isObservationEnabled())
                            .isTrue();
                    assertThat(container.isAutoStartup()).isFalse();
                });
    }

    @Test
    void createsPerSubscriptionDltHandlerOnlyWhenApplicationProvidesFailurePolicy() {
        KafkaConsumerFailurePolicy failurePolicy = new KafkaConsumerFailurePolicy(
                new FixedBackOff(0, 0),
                failure -> MessageFailureClassification.nonRetryable(MessageFailureCategory.HANDLER));
        @SuppressWarnings("unchecked")
        KafkaOperations<Object, Object> kafkaOperations = mock(KafkaOperations.class);

        kafkaRunner()
                .withBean(KafkaOperations.class, () -> kafkaOperations)
                .withBean(
                        KafkaConsumerFailurePolicyResolver.class,
                        () -> KafkaConsumerFailurePolicyResolver.fixed(failurePolicy))
                .run(context -> {
                    assertThat(context).hasSingleBean(KafkaSubscriptionErrorHandlerFactory.class);
                    ResolvedMessageSubscription subscription = new ResolvedMessageSubscription(
                            "allocation", "allocation", Map.of("order-events", "orders"));
                    assertThat(context.getBean(KafkaSubscriptionErrorHandlerFactory.class)
                                    .create(subscription))
                            .isPresent();
                });

        kafkaRunner().run(context -> assertThat(context).doesNotHaveBean(KafkaSubscriptionErrorHandlerFactory.class));
    }

    @Test
    void createsTypedDispatcherFactoryOnlyForAnExplicitNameMapping() {
        kafkaRunner()
                .withConfiguration(AutoConfigurations.of(MessagingIntegrationEventDispatcherAutoConfiguration.class))
                .withBean(
                        com.flowzati.archone.messaging.events.IntegrationEventNameMapping.class,
                        () -> MapBasedIntegrationEventNameMapping.builder().build())
                .run(context -> {
                    assertThat(context).hasSingleBean(IntegrationEventDispatcherFactory.class);
                    assertThat(context).hasSingleBean(UnhandledIntegrationEventObserver.class);
                });
    }

    @Test
    void kafkaRuntimeAndFlywayFactoryHaveIndependentOptInProperties() {
        kafkaRunner()
                .withPropertyValues("archone.messaging.consumer.kafka.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(MessageConsumer.class));

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(MessagingFlywayAutoConfiguration.class))
                .run(context -> assertThat(context).doesNotHaveBean(MessagingFlywayFactory.class));

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(MessagingFlywayAutoConfiguration.class))
                .withPropertyValues("archone.messaging.flyway.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(MessagingFlywayFactory.class));
    }

    private ApplicationContextRunner jdbcRunner() {
        return coreRunner
                .withConfiguration(AutoConfigurations.of(
                        MessagingJdbcAutoConfiguration.class,
                        MessagingProducerJdbcAutoConfiguration.class,
                        MessagingConsumerJdbcAutoConfiguration.class,
                        MessagingIntegrationEventPublisherAutoConfiguration.class))
                .withBean(
                        com.flowzati.archone.messaging.events.IntegrationEventNameMapping.class,
                        () -> MapBasedIntegrationEventNameMapping.builder().build())
                .withBean(JdbcOperations.class, () -> mock(JdbcOperations.class))
                .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class));
    }

    private ApplicationContextRunner kafkaRunner() {
        return coreRunner
                .withConfiguration(AutoConfigurations.of(MessagingKafkaConsumerAutoConfiguration.class))
                .withBean(ConcurrentKafkaListenerContainerFactory.class, ConcurrentKafkaListenerContainerFactory::new);
    }

    private ApplicationContextRunner bootKafkaRunner() {
        return coreRunner.withConfiguration(
                AutoConfigurations.of(KafkaAutoConfiguration.class, MessagingKafkaConsumerAutoConfiguration.class));
    }
}
