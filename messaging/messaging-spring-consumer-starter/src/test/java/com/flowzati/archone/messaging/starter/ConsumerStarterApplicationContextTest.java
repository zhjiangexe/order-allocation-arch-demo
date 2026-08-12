package com.flowzati.archone.messaging.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.flowzati.archone.messaging.api.MessageConsumer;
import com.flowzati.archone.messaging.api.MessageProducer;
import com.flowzati.archone.messaging.consumer.common.DuplicateMessageDetector;
import com.flowzati.archone.messaging.consumer.observation.ConsumerObservationDecorator;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.util.ClassUtils;

class ConsumerStarterApplicationContextTest {

  @Test
  void consumerOnlyClasspathLoadsInboxAndKafkaWithoutOutboxProducerRuntime() {
    new ApplicationContextRunner()
        .withUserConfiguration(TestApplication.class)
        .withPropertyValues(
            "spring.autoconfigure.exclude="
                + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
        .withBean(ObjectMapper.class, ObjectMapper::new)
        .withBean(JdbcOperations.class, () -> mock(JdbcOperations.class))
        .withBean(
            PlatformTransactionManager.class,
            () -> mock(PlatformTransactionManager.class))
        .withBean(ObservationRegistry.class, ObservationRegistry::create)
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).hasSingleBean(MessageConsumer.class);
          assertThat(context).hasSingleBean(DuplicateMessageDetector.class);
          assertThat(context).hasSingleBean(ConsumerObservationDecorator.class);
          assertThat(context).doesNotHaveBean(MessageProducer.class);
          assertThat(ClassUtils.isPresent(
              "com.flowzati.archone.messaging.producer.jdbc."
                  + "JdbcOutboxMessageProducerImplementation",
              context.getClassLoader())).isFalse();
          assertThat(ClassUtils.isPresent(
              "com.flowzati.archone.messaging.producer.observation."
                  + "ProducerObservationInterceptor",
              context.getClassLoader())).isFalse();
        });
  }

  @Configuration(proxyBeanMethods = false)
  @EnableAutoConfiguration
  static class TestApplication {
  }
}
