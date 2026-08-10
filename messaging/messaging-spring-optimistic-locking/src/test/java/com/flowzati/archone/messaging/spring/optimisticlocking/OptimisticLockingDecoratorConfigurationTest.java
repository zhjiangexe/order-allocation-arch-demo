package com.flowzati.archone.messaging.spring.optimisticlocking;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

class OptimisticLockingDecoratorConfigurationTest {

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner();

  @Test
  void importsTheOptionalDecoratorWithFallbackSettings() {
    contextRunner
        .withUserConfiguration(DefaultConfiguration.class)
        .run(context -> {
          assertThat(context).hasSingleBean(OptimisticLockingDecorator.class);
          assertThat(context.getBean(OptimisticLockingRetrySettings.class))
              .isEqualTo(OptimisticLockingRetrySettings.defaults());
        });
  }

  @Test
  void letsTheApplicationOwnRetrySettings() {
    contextRunner
        .withUserConfiguration(OverriddenConfiguration.class)
        .run(context -> {
          assertThat(context).hasSingleBean(OptimisticLockingDecorator.class);
          assertThat(context.getBean(OptimisticLockingRetrySettings.class))
              .isEqualTo(new OptimisticLockingRetrySettings(4, Duration.ofMillis(25)));
        });
  }

  @Configuration(proxyBeanMethods = false)
  @Import(OptimisticLockingDecoratorConfiguration.class)
  static class DefaultConfiguration {
  }

  @Configuration(proxyBeanMethods = false)
  @Import(OptimisticLockingDecoratorConfiguration.class)
  static class OverriddenConfiguration {

    @Bean
    OptimisticLockingRetrySettings applicationRetrySettings() {
      return new OptimisticLockingRetrySettings(4, Duration.ofMillis(25));
    }
  }
}
