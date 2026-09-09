package com.flowzati.archone.testsupport;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.testcontainers.postgresql.PostgreSQLContainer;

@TestConfiguration(proxyBeanMethods = false)
@Import(InProcessMessagingTestConfiguration.class)
public class PostgreSQLTestConfiguration {

    public static final Instant NOW = Instant.parse("2026-08-27T02:00:00Z");

    private static final String POSTGRES_IMAGE = "postgres:16-alpine";

    /** 與 StockFixtures 的固定效期搭配，避免測試結果隨執行日期改變。 */
    @Bean
    @Primary
    Clock integrationTestClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(POSTGRES_IMAGE)
                .withDatabaseName("order_promising_test")
                .withUsername("order_promising")
                .withPassword("order_promising");
    }
}
