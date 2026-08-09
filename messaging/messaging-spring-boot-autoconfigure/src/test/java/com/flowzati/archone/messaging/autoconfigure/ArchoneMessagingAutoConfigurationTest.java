package com.flowzati.archone.messaging.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowzati.archone.messaging.api.MessageProducer;
import com.flowzati.archone.messaging.api.MessageBuilder;
import com.flowzati.archone.messaging.consumer.common.DuplicateMessageDetector;
import com.flowzati.archone.messaging.consumer.jdbc.TransactionalIdempotencyMessageHandlerDecorator;
import com.flowzati.archone.messaging.events.IntegrationEventDeserializer;
import com.flowzati.archone.messaging.events.IntegrationEventPublisher;
import com.flowzati.archone.messaging.events.IntegrationEventSerializer;
import com.flowzati.archone.messaging.inbox.InboxRepo;
import com.flowzati.archone.messaging.inbox.DuplicateMessageDetectorInboxRepo;
import com.flowzati.archone.messaging.inbox.infrastructure.jpa.JpaEventInboxRepository;
import com.flowzati.archone.messaging.kafka.KafkaIntegrationEventDispatcher;
import com.flowzati.archone.messaging.outbox.OutboxRepo;
import com.flowzati.archone.messaging.outbox.infrastructure.jpa.JpaOutboxRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.transaction.PlatformTransactionManager;

class ArchoneMessagingAutoConfigurationTest {

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(ArchoneMessagingAutoConfiguration.class))
      .withBean(ObjectMapper.class, () -> new ObjectMapper().findAndRegisterModules());

  @Test
  void providesSerdeAndKafkaDispatcherWithoutGeneratingListeners() {
    contextRunner.run(context -> {
      assertThat(context).hasSingleBean(IntegrationEventSerializer.class);
      assertThat(context).hasSingleBean(IntegrationEventDeserializer.class);
      assertThat(context).hasSingleBean(KafkaIntegrationEventDispatcher.class);
      assertThat(context).doesNotHaveBean(InboxRepo.class);
      assertThat(context).doesNotHaveBean(OutboxRepo.class);
    });
  }

  @Test
  void createsAnEventScopedSerdeWithoutAnApplicationJacksonTwoMapper() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ArchoneMessagingAutoConfiguration.class))
        .run(context -> {
          assertThat(context).hasSingleBean(IntegrationEventSerializer.class);
          assertThat(context).hasSingleBean(IntegrationEventDeserializer.class);
          assertThat(context).doesNotHaveBean(ObjectMapper.class);
        });
  }

  @Test
  void composesJpaBridgesAndTheJdbcProducerWhenRequiredInfrastructureExists() {
    contextRunner.withConfiguration(
            AutoConfigurations.of(
                ArchoneMessagingJdbcProducerAutoConfiguration.class,
                ArchoneMessagingJdbcConsumerAutoConfiguration.class,
                ArchoneMessagingJpaAutoConfiguration.class,
                ArchoneIntegrationEventPublisherAutoConfiguration.class))
        .withBean(JdbcOperations.class, () -> mock(JdbcOperations.class))
        .withBean(
            PlatformTransactionManager.class,
            () -> mock(PlatformTransactionManager.class))
        .withBean(JpaEventInboxRepository.class, () -> mock(JpaEventInboxRepository.class))
        .withBean(JpaOutboxRepository.class, () -> mock(JpaOutboxRepository.class))
        .run(context -> {
          assertThat(context).hasSingleBean(InboxRepo.class);
          assertThat(context.getBean(InboxRepo.class))
              .isInstanceOf(DuplicateMessageDetectorInboxRepo.class);
          assertThat(context).hasSingleBean(DuplicateMessageDetector.class);
          assertThat(context)
              .hasSingleBean(TransactionalIdempotencyMessageHandlerDecorator.class);
          assertThat(context).hasSingleBean(OutboxRepo.class);
          assertThat(context).hasSingleBean(MessageProducer.class);
          assertThat(context).hasSingleBean(IntegrationEventPublisher.class);
        });
  }

  @Test
  void backsOffWhenTheApplicationProvidesItsOwnPublisher() {
    IntegrationEventPublisher customPublisher = mock(IntegrationEventPublisher.class);
    MessageProducer customProducer = mock(MessageProducer.class);

    contextRunner.withConfiguration(
            AutoConfigurations.of(
                ArchoneMessagingJdbcProducerAutoConfiguration.class,
                ArchoneIntegrationEventPublisherAutoConfiguration.class))
        .withBean(MessageProducer.class, () -> customProducer)
        .withBean(IntegrationEventPublisher.class, () -> customPublisher)
        .run(context -> assertThat(context.getBean(IntegrationEventPublisher.class))
            .isSameAs(customPublisher));
  }

  @Test
  void createsATypedPublisherForAnApplicationProvidedMessageProducer() {
    MessageProducer customProducer = mock(MessageProducer.class);

    contextRunner.withConfiguration(
            AutoConfigurations.of(ArchoneIntegrationEventPublisherAutoConfiguration.class))
        .withBean(MessageProducer.class, () -> customProducer)
        .run(context -> assertThat(context).hasSingleBean(IntegrationEventPublisher.class));
  }

  @Test
  void disablingJpaBridgesDoesNotDisableTheJdbcProducer() {
    contextRunner.withConfiguration(
            AutoConfigurations.of(
                ArchoneMessagingJdbcProducerAutoConfiguration.class,
                ArchoneMessagingJpaAutoConfiguration.class,
                ArchoneIntegrationEventPublisherAutoConfiguration.class))
        .withBean(JdbcOperations.class, () -> mock(JdbcOperations.class))
        .withBean(
            PlatformTransactionManager.class,
            () -> mock(PlatformTransactionManager.class))
        .withPropertyValues("archone.messaging.jpa.enabled=false")
        .run(context -> {
          assertThat(context).doesNotHaveBean(InboxRepo.class);
          assertThat(context).doesNotHaveBean(OutboxRepo.class);
          assertThat(context).hasSingleBean(MessageProducer.class);
          assertThat(context).hasSingleBean(IntegrationEventPublisher.class);
        });
  }

  @Test
  @SuppressWarnings("removal")
  void composesJdbcConsumerWithoutJpaRepositories() {
    contextRunner.withConfiguration(
            AutoConfigurations.of(ArchoneMessagingJdbcConsumerAutoConfiguration.class))
        .withBean(JdbcOperations.class, () -> mock(JdbcOperations.class))
        .withBean(
            PlatformTransactionManager.class,
            () -> mock(PlatformTransactionManager.class))
        .run(context -> {
          assertThat(context).hasSingleBean(DuplicateMessageDetector.class);
          assertThat(context)
              .hasSingleBean(TransactionalIdempotencyMessageHandlerDecorator.class);
          assertThat(context).hasSingleBean(InboxRepo.class);
          assertThat(context).doesNotHaveBean(JpaEventInboxRepository.class);
        });
  }

  @Test
  void canDisableTheJdbcConsumerAndKeepTheJpaFallback() {
    contextRunner.withConfiguration(
            AutoConfigurations.of(
                ArchoneMessagingJdbcConsumerAutoConfiguration.class,
                ArchoneMessagingJpaAutoConfiguration.class))
        .withBean(JdbcOperations.class, () -> mock(JdbcOperations.class))
        .withBean(
            PlatformTransactionManager.class,
            () -> mock(PlatformTransactionManager.class))
        .withBean(JpaEventInboxRepository.class, () -> mock(JpaEventInboxRepository.class))
        .withBean(JpaOutboxRepository.class, () -> mock(JpaOutboxRepository.class))
        .withPropertyValues("archone.messaging.consumer.jdbc.enabled=false")
        .run(context -> {
          assertThat(context).doesNotHaveBean(DuplicateMessageDetector.class);
          assertThat(context).hasSingleBean(InboxRepo.class);
        });
  }

  @Test
  void canDisableTheJdbcProducerIndependently() {
    contextRunner.withConfiguration(
            AutoConfigurations.of(
                ArchoneMessagingJdbcProducerAutoConfiguration.class,
                ArchoneIntegrationEventPublisherAutoConfiguration.class))
        .withBean(JdbcOperations.class, () -> mock(JdbcOperations.class))
        .withBean(
            PlatformTransactionManager.class,
            () -> mock(PlatformTransactionManager.class))
        .withPropertyValues("archone.messaging.producer.jdbc.enabled=false")
        .run(context -> {
          assertThat(context).doesNotHaveBean(MessageProducer.class);
          assertThat(context).doesNotHaveBean(IntegrationEventPublisher.class);
        });
  }

  @Test
  void rejectsOutboxProductionWithoutAnActiveCallerTransaction() {
    contextRunner.withConfiguration(
            AutoConfigurations.of(ArchoneMessagingJdbcProducerAutoConfiguration.class))
        .withBean(JdbcOperations.class, () -> mock(JdbcOperations.class))
        .withBean(
            PlatformTransactionManager.class,
            () -> mock(PlatformTransactionManager.class))
        .run(context -> assertThatThrownBy(() -> context.getBean(MessageProducer.class).send(
            "order-events",
            MessageBuilder.withPayload("{}")
                .withType("OrderPlaced.v1")
                .withPartitionId("order-1")
                .build()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("No active caller transaction for transactional message production"));
  }
}
