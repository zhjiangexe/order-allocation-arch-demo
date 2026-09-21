package com.flowzati.archone.messaging.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.flowzati.archone.messaging.api.MessageConsumer;
import com.flowzati.archone.messaging.api.MessageProducer;
import com.flowzati.archone.messaging.events.IntegrationEventPublisher;
import com.flowzati.archone.messaging.producer.observation.ProducerObservationInterceptor;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.util.ClassUtils;
import tools.jackson.databind.ObjectMapper;

class ProducerStarterApplicationContextTest {

    @Test
    void producerOnlyClasspathLoadsOutboxWithoutInboxOrKafkaConsumerRuntime() {
        new ApplicationContextRunner()
                .withUserConfiguration(TestApplication.class)
                .withPropertyValues("spring.autoconfigure.exclude="
                        + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(JdbcOperations.class, () -> mock(JdbcOperations.class))
                .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
                .withBean(ObservationRegistry.class, ObservationRegistry::create)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(MessageProducer.class);
                    assertThat(context).hasSingleBean(IntegrationEventPublisher.class);
                    assertThat(context).hasSingleBean(ProducerObservationInterceptor.class);
                    assertThat(context).doesNotHaveBean(MessageConsumer.class);
                    assertThat(ClassUtils.isPresent(
                                    "com.flowzati.archone.messaging.consumer.jdbc.SqlTableBasedDuplicateMessageDetector",
                                    context.getClassLoader()))
                            .isFalse();
                    assertThat(ClassUtils.isPresent(
                                    "com.flowzati.archone.messaging.spring.consumer.kafka."
                                            + "SpringKafkaMessageConsumerImplementation",
                                    context.getClassLoader()))
                            .isFalse();
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class TestApplication {}
}
