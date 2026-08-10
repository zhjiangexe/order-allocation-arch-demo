package com.flowzati.archone.messaging.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowzati.archone.messaging.api.MessageInterceptor;
import com.flowzati.archone.messaging.consumer.observation.ConsumerObservationDecorator;
import com.flowzati.archone.messaging.producer.observation.ProducerObservationInterceptor;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ArchoneMessagingObservationAutoConfigurationTest {

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withConfiguration(
          AutoConfigurations.of(ArchoneMessagingObservationAutoConfiguration.class));

  @Test
  void createsBothAdaptersOnlyWhenAnObservationRegistryExists() {
    contextRunner.run(context -> {
      assertThat(context).doesNotHaveBean(ProducerObservationInterceptor.class);
      assertThat(context).doesNotHaveBean(ConsumerObservationDecorator.class);
    });

    contextRunner
        .withBean(ObservationRegistry.class, ObservationRegistry::create)
        .run(context -> {
          assertThat(context).hasSingleBean(ProducerObservationInterceptor.class);
          assertThat(context).hasSingleBean(ConsumerObservationDecorator.class);
        });
  }

  @Test
  void supportsGlobalAndPerDirectionOptOut() {
    contextRunner
        .withBean(ObservationRegistry.class, ObservationRegistry::create)
        .withPropertyValues("archone.messaging.observation.enabled=false")
        .run(context -> {
          assertThat(context).doesNotHaveBean(ProducerObservationInterceptor.class);
          assertThat(context).doesNotHaveBean(ConsumerObservationDecorator.class);
        });

    contextRunner
        .withBean(ObservationRegistry.class, ObservationRegistry::create)
        .withPropertyValues("archone.messaging.observation.producer.enabled=false")
        .run(context -> {
          assertThat(context).doesNotHaveBean(ProducerObservationInterceptor.class);
          assertThat(context).hasSingleBean(ConsumerObservationDecorator.class);
        });
  }

  @Test
  void backsOffForApplicationProvidedAdaptersWithoutRequiringAnExporter() {
    ObservationRegistry registry = ObservationRegistry.create();
    ProducerObservationInterceptor customProducer =
        new ProducerObservationInterceptor(registry);
    ConsumerObservationDecorator customConsumer =
        new ConsumerObservationDecorator(registry);

    contextRunner
        .withBean(ObservationRegistry.class, () -> registry)
        .withBean(ProducerObservationInterceptor.class, () -> customProducer)
        .withBean(ConsumerObservationDecorator.class, () -> customConsumer)
        .run(context -> {
          assertThat(context.getBean(ProducerObservationInterceptor.class))
              .isSameAs(customProducer);
          assertThat(context.getBean(ConsumerObservationDecorator.class))
              .isSameAs(customConsumer);
          assertThat(context).doesNotHaveBean("meterRegistry");
        });
  }

  @Test
  void ordersProducerObservationAfterApplicationPreSendInterceptors() {
    MessageInterceptor applicationInterceptor = new MessageInterceptor() { };

    contextRunner
        .withBean(ObservationRegistry.class, ObservationRegistry::create)
        .withBean(MessageInterceptor.class, () -> applicationInterceptor)
        .run(context -> assertThat(context.getBeanProvider(MessageInterceptor.class)
            .orderedStream())
            .containsExactly(
                applicationInterceptor,
                context.getBean(ProducerObservationInterceptor.class)));
  }
}
