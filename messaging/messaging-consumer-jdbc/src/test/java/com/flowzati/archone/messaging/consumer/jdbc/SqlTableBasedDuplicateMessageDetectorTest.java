package com.flowzati.archone.messaging.consumer.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowzati.archone.messaging.jdbc.JdbcStatementExecutor;
import com.flowzati.archone.messaging.jdbc.MessagingSchema;
import com.flowzati.archone.messaging.jdbc.MessagingTableNames;
import com.flowzati.archone.messaging.jdbc.PostgresMessagingSqlDialect;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SqlTableBasedDuplicateMessageDetectorTest {

  private static final UUID MESSAGE_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000030");
  private static final Instant PROCESSED_AT = Instant.parse("2026-08-09T13:00:00Z");

  @Test
  void atomicallyClaimsAValidatedCustomInboxTable() {
    CapturingExecutor executor = new CapturingExecutor();
    SqlTableBasedDuplicateMessageDetector detector = detector(executor);

    assertThat(detector.claimIfNew("stock-allocation", MESSAGE_ID, "OrderPlaced.v1"))
        .isTrue();
    assertThat(executor.sql).isEqualTo(
        "INSERT INTO tenant_messaging.received_messages "
            + "(subscriber_id, event_id, event_type, processed_at) VALUES (?, ?, ?, ?) "
            + "ON CONFLICT (subscriber_id, event_id) DO NOTHING");
    assertThat(executor.arguments).containsExactly(
        "stock-allocation",
        MESSAGE_ID,
        "OrderPlaced.v1",
        OffsetDateTime.ofInstant(PROCESSED_AT, ZoneOffset.UTC));
  }

  @Test
  void reportsAZeroRowAtomicInsertAsDuplicate() {
    CapturingExecutor executor = new CapturingExecutor();
    executor.rows = 0;

    assertThat(detector(executor).claimIfNew(
        "stock-allocation", MESSAGE_ID, "OrderPlaced.v1")).isFalse();
  }

  @Test
  void rejectsInvalidClaimFieldsBeforeJdbcExecution() {
    CapturingExecutor executor = new CapturingExecutor();

    assertThatThrownBy(() -> detector(executor).claimIfNew(" ", MESSAGE_ID, "OrderPlaced.v1"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Inbox claim fields are required");
    assertThat(executor.arguments).isNull();
  }

  @Test
  void rejectsAnUnexpectedJdbcRowCount() {
    CapturingExecutor executor = new CapturingExecutor();
    executor.rows = 2;

    assertThatThrownBy(() -> detector(executor).claimIfNew(
        "stock-allocation", MESSAGE_ID, "OrderPlaced.v1"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Inbox claim affected an unexpected row count: 2");
  }

  @Test
  void rejectsUnsafeCustomIdentifiersDuringConstruction() {
    assertThatThrownBy(() -> new SqlTableBasedDuplicateMessageDetector(
        new CapturingExecutor(),
        new PostgresMessagingSqlDialect(),
        MessagingSchema.defaultSchema(),
        new MessagingTableNames("event_inbox; DELETE FROM orders", "event_outbox"),
        Clock.systemUTC()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Invalid Inbox table SQL identifier");
  }

  private SqlTableBasedDuplicateMessageDetector detector(CapturingExecutor executor) {
    return new SqlTableBasedDuplicateMessageDetector(
        executor,
        new PostgresMessagingSqlDialect(),
        MessagingSchema.named("tenant_messaging"),
        new MessagingTableNames("received_messages", "event_outbox"),
        Clock.fixed(PROCESSED_AT, ZoneOffset.UTC));
  }

  private static final class CapturingExecutor implements JdbcStatementExecutor {
    private int rows = 1;
    private String sql;
    private List<Object> arguments;

    @Override
    public int update(String sql, List<?> arguments) {
      this.sql = sql;
      this.arguments = new ArrayList<>(arguments);
      return rows;
    }
  }
}
