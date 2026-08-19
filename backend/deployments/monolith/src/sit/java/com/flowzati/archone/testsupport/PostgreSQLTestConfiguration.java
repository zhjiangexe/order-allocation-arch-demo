package com.flowzati.archone.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.testcontainers.postgresql.PostgreSQLContainer;

@TestConfiguration(proxyBeanMethods = false)
@Import(InProcessMessagingTestConfiguration.class)
public class PostgreSQLTestConfiguration {

    private static final String POSTGRES_IMAGE = "postgres:16-alpine";

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(POSTGRES_IMAGE)
                .withDatabaseName("order_promising_test")
                .withUsername("order_promising")
                .withPassword("order_promising");
    }
}
