package com.flowzati.archone.messaging.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.flowzati.archone.messaging.api.ChannelMapping;
import com.flowzati.archone.messaging.api.MessageConsumer;
import com.flowzati.archone.messaging.api.MessageProducer;
import com.flowzati.archone.messaging.consumer.common.DuplicateMessageDetector;
import com.flowzati.archone.messaging.spring.consumer.kafka.KafkaSubscriptionPolicy;
import com.flowzati.archone.messaging.spring.consumer.kafka.KafkaSubscriptionPolicyResolver;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.transaction.PlatformTransactionManager;

class AllInOneStarterApplicationContextTest {

  @Test
  void allInOneLoadsBothDirectionsAndPreservesApplicationOverrides() {
    ChannelMapping customMapping = logical -> "deployment." + logical;
    KafkaSubscriptionPolicyResolver customPolicy =
        KafkaSubscriptionPolicyResolver.fixed(KafkaSubscriptionPolicy.defaults());

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
        .withBean(ChannelMapping.class, () -> customMapping)
        .withBean(KafkaSubscriptionPolicyResolver.class, () -> customPolicy)
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).hasSingleBean(MessageProducer.class);
          assertThat(context).hasSingleBean(MessageConsumer.class);
          assertThat(context).hasSingleBean(DuplicateMessageDetector.class);
          assertThat(context.getBean(ChannelMapping.class)).isSameAs(customMapping);
          assertThat(context.getBean(KafkaSubscriptionPolicyResolver.class))
              .isSameAs(customPolicy);
        });
  }

  @Configuration(proxyBeanMethods = false)
  @EnableAutoConfiguration
  static class TestApplication {
  }
}
