package com.flowzati.archone.messaging.spring.flyway;

import com.flowzati.archone.messaging.jdbc.MessagingSchema;
import com.flowzati.archone.messaging.jdbc.MessagingTableNames;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;

/** Configures an isolated location, schema, and history table for opt-in messaging migrations. */
public final class DefaultMessagingFlywayFactory implements MessagingFlywayFactory {

    public static final String MIGRATION_LOCATION = "classpath:db/migration/archone-messaging";
    public static final String HISTORY_TABLE = "flyway_archone_messaging_schema_history";

    @Override
    public Flyway create(DataSource dataSource, MessagingSchema schema, MessagingTableNames tableNames) {
        Objects.requireNonNull(dataSource, "Messaging Flyway DataSource is required");
        Objects.requireNonNull(schema, "Messaging Flyway schema is required");
        Objects.requireNonNull(tableNames, "Messaging Flyway table names are required");
        String schemaName = schema.name()
                .orElseThrow(() -> new IllegalArgumentException("Opt-in messaging Flyway requires a named schema"));

        return Flyway.configure()
                .dataSource(dataSource)
                .locations(MIGRATION_LOCATION)
                .schemas(schemaName)
                .defaultSchema(schemaName)
                .createSchemas(true)
                .table(HISTORY_TABLE)
                .placeholders(Map.of(
                        "messagingSchema", schemaName,
                        "inboxTable", tableNames.inbox(),
                        "outboxTable", tableNames.outbox()))
                .cleanDisabled(true)
                .load();
    }
}
