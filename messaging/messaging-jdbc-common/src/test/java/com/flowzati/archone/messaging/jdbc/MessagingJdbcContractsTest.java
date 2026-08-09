package com.flowzati.archone.messaging.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MessagingJdbcContractsTest {

  private final MessagingSqlDialect dialect = new PostgresMessagingSqlDialect();

  @Test
  void qualifiesDefaultAndNamedApplicationTables() {
    MessagingTableNames tables = MessagingTableNames.defaults();

    assertThat(MessagingSchema.defaultSchema().qualify(tables.inbox()))
        .isEqualTo("event_inbox");
    assertThat(MessagingSchema.named("tenant_messaging").qualify(tables.outbox()))
        .isEqualTo("tenant_messaging.event_outbox");
  }

  @Test
  void generatesPostgresInsertAndAtomicDuplicateClaimSql() {
    assertThat(dialect.insert(
        "event_outbox", List.of("id", "payload", "timestamp")))
        .isEqualTo("INSERT INTO event_outbox (id, payload, timestamp) VALUES (?, ?, ?)");
    assertThat(dialect.insertIgnoringDuplicate(
        "messaging.event_inbox",
        List.of("subscriber_id", "event_id", "processed_at"),
        List.of("subscriber_id", "event_id")))
        .isEqualTo("INSERT INTO messaging.event_inbox "
            + "(subscriber_id, event_id, processed_at) VALUES (?, ?, ?) "
            + "ON CONFLICT (subscriber_id, event_id) DO NOTHING");
  }

  @Test
  void castsOnlyDeclaredJsonParameters() {
    assertThat(dialect.insert(
        "event_outbox", List.of("id", "payload", "headers"), Set.of("payload")))
        .isEqualTo(
            "INSERT INTO event_outbox (id, payload, headers) VALUES (?, CAST(? AS jsonb), ?)");
  }

  @Test
  void rejectsUnsafeRuntimeIdentifiers() {
    assertThatThrownBy(() -> MessagingSchema.named("public; DROP TABLE orders"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Invalid schema SQL identifier");
    assertThatThrownBy(() -> new MessagingTableNames("event_inbox --", "event_outbox"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Invalid Inbox table SQL identifier");
    assertThatThrownBy(() -> dialect.insert(
        "event_outbox", List.of("id", "payload) VALUES (?, ?); DELETE FROM orders; --")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Invalid column SQL identifier");
  }

  @Test
  void requiresConflictColumnsToBelongToTheInsert() {
    assertThatThrownBy(() -> dialect.insertIgnoringDuplicate(
        "event_inbox",
        List.of("subscriber_id", "event_id"),
        List.of("subscriber_id", "missing_column")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Conflict columns must be included in insert columns");
  }
}
