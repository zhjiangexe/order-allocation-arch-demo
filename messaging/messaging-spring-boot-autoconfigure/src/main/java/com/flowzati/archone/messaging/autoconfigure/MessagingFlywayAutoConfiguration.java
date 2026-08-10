package com.flowzati.archone.messaging.autoconfigure;

import com.flowzati.archone.messaging.spring.flyway.DefaultMessagingFlywayFactory;
import com.flowzati.archone.messaging.spring.flyway.MessagingFlywayFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/** Opt-in access to namespaced migrations; it never creates a Flyway initializer. */
@AutoConfiguration
@ConditionalOnClass(MessagingFlywayFactory.class)
@ConditionalOnProperty(
    prefix = "archone.messaging.flyway",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = false
)
public class MessagingFlywayAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  MessagingFlywayFactory messagingFlywayFactory() {
    return new DefaultMessagingFlywayFactory();
  }
}
