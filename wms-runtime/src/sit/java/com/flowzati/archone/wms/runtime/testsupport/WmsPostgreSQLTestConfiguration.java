package com.flowzati.archone.wms.runtime.testsupport;

import com.flowzati.archone.messaging.testsupport.ControllableMessageConsumerImplementation;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** PostgreSQL plus an in-process transport; all production decorators and dispatchers remain active. */
@TestConfiguration(proxyBeanMethods = false)
public class WmsPostgreSQLTestConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("wms_test")
                .withUsername("wms")
                .withPassword("wms");
    }

    @Bean
    ControllableMessageConsumerImplementation controllableMessageConsumerImplementation() {
        return new ControllableMessageConsumerImplementation();
    }
}
