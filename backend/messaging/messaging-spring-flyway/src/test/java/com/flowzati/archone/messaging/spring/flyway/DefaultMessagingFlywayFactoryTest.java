package com.flowzati.archone.messaging.spring.flyway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.flowzati.archone.messaging.jdbc.MessagingSchema;
import com.flowzati.archone.messaging.jdbc.MessagingTableNames;
import java.nio.charset.StandardCharsets;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class DefaultMessagingFlywayFactoryTest {

    @Test
    void createsAnIsolatedConfigurationWithoutRunningMigrations() throws Exception {
        DataSource dataSource = mock(DataSource.class);

        Flyway flyway = new DefaultMessagingFlywayFactory().create(dataSource);

        assertThat(flyway.getConfiguration().getDefaultSchema()).isEqualTo(MessagingFlywayFactory.DEFAULT_SCHEMA);
        assertThat(flyway.getConfiguration().getSchemas()).containsExactly(MessagingFlywayFactory.DEFAULT_SCHEMA);
        assertThat(flyway.getConfiguration().getTable()).isEqualTo(DefaultMessagingFlywayFactory.HISTORY_TABLE);
        assertThat(flyway.getConfiguration().getLocations())
                .extracting(Object::toString)
                .containsExactly(DefaultMessagingFlywayFactory.MIGRATION_LOCATION);
        assertThat(flyway.getConfiguration().getPlaceholders())
                .containsEntry("messagingSchema", MessagingFlywayFactory.DEFAULT_SCHEMA)
                .containsEntry("inboxTable", MessagingTableNames.DEFAULT_INBOX)
                .containsEntry("outboxTable", MessagingTableNames.DEFAULT_OUTBOX);
        verifyNoInteractions(dataSource);

        String migration = new String(
                getClass()
                        .getResourceAsStream("/db/migration/archone-messaging/V1__create_messaging_schema.sql")
                        .readAllBytes(),
                StandardCharsets.UTF_8);
        assertThat(migration)
                .contains("${messagingSchema}.${inboxTable}")
                .contains("${messagingSchema}.${outboxTable}")
                .contains("headers TEXT NOT NULL DEFAULT '{}'");
    }

    @Test
    void refusesTheApplicationDefaultSchemaToPreventHistoryCollisions() {
        assertThatThrownBy(() -> new DefaultMessagingFlywayFactory()
                        .create(
                                mock(DataSource.class),
                                MessagingSchema.defaultSchema(),
                                MessagingTableNames.defaults()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Opt-in messaging Flyway requires a named schema");
    }
}
