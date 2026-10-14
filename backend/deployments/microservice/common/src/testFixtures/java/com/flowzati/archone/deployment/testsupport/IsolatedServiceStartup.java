package com.flowzati.archone.deployment.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowzati.archone.messaging.spring.optimisticlocking.OptimisticLockingDecorator;
import com.flowzati.archone.messaging.spring.optimisticlocking.OptimisticLockingRetryObserver;
import jakarta.persistence.EntityManagerFactory;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.web.client.RestClient;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Starts each service on its own runtime classpath against real migrated PostgreSQL. */
public abstract class IsolatedServiceStartup {
    protected abstract Class<?> application();

    protected abstract String businessPackage();

    @Test
    void startsWithOnlyItsOwnBusinessComponentsAndEntities() {
        try (var postgres = new PostgreSQLContainer("postgres:16-alpine")) {
            postgres.start();
            Flyway.configure()
                    .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();
            try (var context = new SpringApplicationBuilder(application())
                    .run(
                            "--server.port=0",
                            "--spring.datasource.url=" + postgres.getJdbcUrl(),
                            "--spring.datasource.username=" + postgres.getUsername(),
                            "--spring.datasource.password=" + postgres.getPassword(),
                            "--spring.kafka.listener.auto-startup=false",
                            "--archone.allocation.reconciliation-scheduler-enabled=false",
                            "--archone.wms.simulation.enabled=false")) {
                int port =
                        ((WebServerApplicationContext) context).getWebServer().getPort();
                String health = RestClient.create("http://localhost:" + port)
                        .get()
                        .uri("/actuator/health")
                        .retrieve()
                        .body(String.class);
                assertThat(health).contains("UP");
                if (businessPackage().equals("com.flowzati.archone.logisticsdata")) {
                    assertThat(context.getBeansOfType(OptimisticLockingDecorator.class))
                            .isEmpty();
                } else {
                    assertThat(context.getBeansOfType(OptimisticLockingDecorator.class))
                            .hasSize(1);
                }
                assertThat(context.getBeansOfType(OptimisticLockingRetryObserver.class))
                        .hasSize(businessPackage().equals("com.flowzati.archone.inventory") ? 1 : 0);
                for (var entity : context.getBean(EntityManagerFactory.class)
                        .getMetamodel()
                        .getEntities()) {
                    assertThat(entity.getJavaType().getPackageName()).startsWith(businessPackage());
                }
                for (String other : List.of("ordering", "inventory", "wms", "fulfillment", "logisticsdata")) {
                    String prefix = "com.flowzati.archone." + other;
                    if (!prefix.equals(businessPackage())) {
                        assertThat(context.getBeanDefinitionNames()).noneMatch(name -> {
                            Class<?> type = context.getType(name);
                            return type != null
                                    && type.getName().startsWith(prefix + ".")
                                    && !type.getName().startsWith(prefix + ".api.");
                        });
                    }
                }
            }
        }
    }
}
