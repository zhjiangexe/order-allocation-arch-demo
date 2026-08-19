package com.flowzati.archone.messaging.spring.flyway;

import org.springframework.context.annotation.Bean;

/**
 * Explicitly imported configuration that exposes a factory only. It does not create a Flyway
 * initializer and therefore cannot migrate an application database merely by being on classpath.
 * It has no component stereotype and cannot be activated by component scanning.
 */
public class SpringMessagingFlywayConfiguration {

    @Bean
    public MessagingFlywayFactory messagingFlywayFactory() {
        return new DefaultMessagingFlywayFactory();
    }
}
