package com.flowzati.archone.messaging.autoconfigure;

import com.flowzati.archone.messaging.MessagingPackage;
import com.flowzati.archone.messaging.inbox.infrastructure.jpa.JpaEventInboxRepository;
import com.flowzati.archone.messaging.outbox.infrastructure.jpa.JpaOutboxRepository;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration;

/** Registers legacy repository packages before Spring Data discovery when explicitly retained. */
@AutoConfiguration(before = DataJpaRepositoriesAutoConfiguration.class)
@ConditionalOnClass({JpaEventInboxRepository.class, JpaOutboxRepository.class})
@ConditionalOnProperty(prefix = "archone.messaging.jpa", name = "enabled", matchIfMissing = true)
@ConditionalOnProperty(
    prefix = "archone.messaging.jpa",
    name = "register-package",
    matchIfMissing = true
)
@AutoConfigurationPackage(basePackageClasses = MessagingPackage.class)
public class MessagingJpaPackageAutoConfiguration {
}
