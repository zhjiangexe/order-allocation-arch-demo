package com.flowzati.archone.messaging.spring.consumer.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.flowzati.archone.messaging.consumer.common.DuplicateMessageDetector;
import com.flowzati.archone.messaging.consumer.jdbc.SqlTableBasedDuplicateMessageDetector;
import com.flowzati.archone.messaging.consumer.jdbc.TransactionalIdempotencyMessageHandlerDecorator;
import com.flowzati.archone.messaging.jdbc.MessagingSchema;
import com.flowzati.archone.messaging.jdbc.MessagingSqlDialect;
import com.flowzati.archone.messaging.jdbc.MessagingTableNames;
import com.flowzati.archone.messaging.jdbc.PostgresMessagingSqlDialect;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.transaction.PlatformTransactionManager;

class SpringJdbcMessageConsumerConfigurationTest {

    @Test
    void composesPureDetectorAndDecoratorFromSpringJdbcPorts() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(Infrastructure.class, SpringJdbcMessageConsumerConfiguration.class);
            context.refresh();

            assertThat(context.getBeansOfType(SqlTableBasedDuplicateMessageDetector.class))
                    .hasSize(1);
            assertThat(context.getBeansOfType(TransactionalIdempotencyMessageHandlerDecorator.class))
                    .hasSize(1);
        }
    }

    @Test
    void letsAnApplicationDetectorReplaceTheFallbackWithoutReplacingTheDecoratorWiring() {
        try (var context = new AnnotationConfigApplicationContext()) {
            DuplicateMessageDetector custom = (subscriberId, messageId, messageType) -> true;
            context.registerBean("customDuplicateMessageDetector", DuplicateMessageDetector.class, () -> custom);
            context.register(Infrastructure.class, SpringJdbcMessageConsumerConfiguration.class);
            context.refresh();

            assertThat(context.getBeansOfType(DuplicateMessageDetector.class)).hasSize(2);
            assertThat(context.getBean(DuplicateMessageDetector.class)).isSameAs(custom);
            assertThat(context.getBeansOfType(TransactionalIdempotencyMessageHandlerDecorator.class))
                    .hasSize(1);
        }
    }

    static class Infrastructure {

        @Bean
        JdbcOperations jdbcOperations() {
            return mock(JdbcOperations.class);
        }

        @Bean
        PlatformTransactionManager transactionManager() {
            return mock(PlatformTransactionManager.class);
        }

        @Bean
        MessagingSqlDialect messagingSqlDialect() {
            return new PostgresMessagingSqlDialect();
        }

        @Bean
        MessagingSchema messagingSchema() {
            return MessagingSchema.defaultSchema();
        }

        @Bean
        MessagingTableNames messagingTableNames() {
            return MessagingTableNames.defaults();
        }

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }
    }
}
