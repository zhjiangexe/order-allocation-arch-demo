package com.flowzati.archone.messaging.consumer.jdbc;

import com.flowzati.archone.messaging.consumer.common.DuplicateMessageDetector;
import com.flowzati.archone.messaging.jdbc.JdbcStatementExecutor;
import com.flowzati.archone.messaging.jdbc.MessagingSchema;
import com.flowzati.archone.messaging.jdbc.MessagingSqlDialect;
import com.flowzati.archone.messaging.jdbc.MessagingTableNames;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Claims one Inbox row with a single database-enforced insert. */
public final class SqlTableBasedDuplicateMessageDetector implements DuplicateMessageDetector {

    private static final List<String> INSERT_COLUMNS =
            List.of("subscriber_id", "event_id", "event_type", "processed_at");
    private static final List<String> CONFLICT_COLUMNS = List.of("subscriber_id", "event_id");

    private final JdbcStatementExecutor statementExecutor;
    private final Clock clock;
    private final String claimSql;

    public SqlTableBasedDuplicateMessageDetector(
            JdbcStatementExecutor statementExecutor,
            MessagingSqlDialect dialect,
            MessagingSchema schema,
            MessagingTableNames tableNames,
            Clock clock) {
        this.statementExecutor = Objects.requireNonNull(statementExecutor, "JDBC statement executor is required");
        Objects.requireNonNull(dialect, "Messaging SQL dialect is required");
        Objects.requireNonNull(schema, "Messaging schema is required");
        Objects.requireNonNull(tableNames, "Messaging table names are required");
        this.clock = Objects.requireNonNull(clock, "Clock is required");
        this.claimSql =
                dialect.insertIgnoringDuplicate(schema.qualify(tableNames.inbox()), INSERT_COLUMNS, CONFLICT_COLUMNS);
    }

    @Override
    public boolean claimIfNew(String subscriberId, UUID messageId, String messageType) {
        if (isBlank(subscriberId) || messageId == null || isBlank(messageType)) {
            throw new IllegalArgumentException("Inbox claim fields are required");
        }

        int affectedRows = statementExecutor.update(
                claimSql,
                List.of(
                        subscriberId,
                        messageId,
                        messageType,
                        OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)));
        if (affectedRows == 1) {
            return true;
        }
        if (affectedRows == 0) {
            return false;
        }
        throw new IllegalStateException("Inbox claim affected an unexpected row count: " + affectedRows);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
