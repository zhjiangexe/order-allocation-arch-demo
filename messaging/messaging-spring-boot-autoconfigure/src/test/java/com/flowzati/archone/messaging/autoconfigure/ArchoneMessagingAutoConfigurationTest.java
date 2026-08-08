package com.flowzati.archone.messaging.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowzati.archone.messaging.api.MessageProducer;
import com.flowzati.archone.messaging.events.IntegrationEventDeserializer;
import com.flowzati.archone.messaging.events.IntegrationEventPublisher;
import com.flowzati.archone.messaging.events.IntegrationEventSerializer;
import com.flowzati.archone.messaging.inbox.InboxRepo;
import com.flowzati.archone.messaging.inbox.infrastructure.jpa.JpaEventInboxRepository;
import com.flowzati.archone.messaging.kafka.KafkaIntegrationEventDispatcher;
import com.flowzati.archone.messaging.outbox.OutboxRepo;
import com.flowzati.archone.messaging.outbox.infrastructure.jpa.JpaOutboxRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

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
  void composesInboxAndOutboxWhenJpaRepositoriesExist() {
    contextRunner.withConfiguration(
            AutoConfigurations.of(ArchoneMessagingJpaAutoConfiguration.class))
        .withBean(JpaEventInboxRepository.class, () -> mock(JpaEventInboxRepository.class))
        .withBean(JpaOutboxRepository.class, () -> mock(JpaOutboxRepository.class))
        .run(context -> {
          assertThat(context).hasSingleBean(InboxRepo.class);
          assertThat(context).hasSingleBean(OutboxRepo.class);
          assertThat(context).hasSingleBean(MessageProducer.class);
          assertThat(context).hasSingleBean(IntegrationEventPublisher.class);
        });
  }

  @Test
  void backsOffWhenTheApplicationProvidesItsOwnPublisher() {
    IntegrationEventPublisher customPublisher = mock(IntegrationEventPublisher.class);

    contextRunner.withConfiguration(
            AutoConfigurations.of(ArchoneMessagingJpaAutoConfiguration.class))
        .withBean(JpaEventInboxRepository.class, () -> mock(JpaEventInboxRepository.class))
        .withBean(JpaOutboxRepository.class, () -> mock(JpaOutboxRepository.class))
        .withBean(IntegrationEventPublisher.class, () -> customPublisher)
        .run(context -> assertThat(context.getBean(IntegrationEventPublisher.class))
            .isSameAs(customPublisher));
  }

  @Test
  void canDisableTheJpaInboxAndOutboxCompositionAsOneCapability() {
    contextRunner.withConfiguration(
            AutoConfigurations.of(ArchoneMessagingJpaAutoConfiguration.class))
        .withPropertyValues("archone.messaging.jpa.enabled=false")
        .run(context -> {
          assertThat(context).doesNotHaveBean(InboxRepo.class);
          assertThat(context).doesNotHaveBean(OutboxRepo.class);
          assertThat(context).doesNotHaveBean(IntegrationEventPublisher.class);
        });
  }
}
