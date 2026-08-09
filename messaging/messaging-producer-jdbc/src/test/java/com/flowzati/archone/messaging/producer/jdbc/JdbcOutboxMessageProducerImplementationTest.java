package com.flowzati.archone.messaging.producer.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowzati.archone.messaging.api.Message;
import com.flowzati.archone.messaging.api.MessageBuilder;
import com.flowzati.archone.messaging.api.MessageHeaders;
import com.flowzati.archone.messaging.jdbc.JdbcStatementExecutor;
import com.flowzati.archone.messaging.jdbc.MessagingSchema;
import com.flowzati.archone.messaging.jdbc.MessagingTableNames;
import com.flowzati.archone.messaging.jdbc.PostgresMessagingSqlDialect;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JdbcOutboxMessageProducerImplementationTest {

  private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000020");
  private static final Instant CREATED_AT = Instant.parse("2026-08-09T12:00:00Z");

  @Test
  void mapsAndAppendsOneJsonbOutboxRowWithoutSpringOrEventTypes() {
    CapturingExecutor executor = new CapturingExecutor();
    JdbcOutboxMessageProducerImplementation producer = producer(executor);

    producer.send("ordering.order-events", eventMessage());

    assertThat(executor.sql).isEqualTo(
        "INSERT INTO tenant_messaging.event_outbox "
            + "(id, aggregatetype, aggregateid, type, route, partition_key, payload, timestamp, "
            + "headers) VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?)");
    assertThat(executor.arguments).containsExactly(
        ID,
        "Order",
        "order-1",
        "OrderPlaced.v1",
        "ordering.order-events",
        "order-1",
        "{\"orderId\":\"order-1\"}",
        OffsetDateTime.ofInstant(CREATED_AT, ZoneOffset.UTC),
        "{\"content-type\":\"application/json\",\"correlation-id\":\"checkout-1\","
            + "\"event-aggregate-id\":\"order-1\",\"event-aggregate-type\":\"Order\","
            + "\"event-contract-version\":\"1\",\"event-type\":\"OrderPlaced.v1\","
            + "\"traceparent\":\"00-abc-def-01\"}");
  }

  @Test
  void rejectsDestinationMismatchBeforeJdbcExecution() {
    CapturingExecutor executor = new CapturingExecutor();

    assertThatThrownBy(() -> producer(executor).send("forged-topic", eventMessage()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Outbox destination does not match message header");
    assertThat(executor.arguments).isNull();
  }

  @Test
  void rejectsAnUnexpectedJdbcRowCount() {
    CapturingExecutor executor = new CapturingExecutor();
    executor.rows = 0;

    assertThatThrownBy(() -> producer(executor).send("ordering.order-events", eventMessage()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Outbox append affected an unexpected row count: 0");
  }

  private JdbcOutboxMessageProducerImplementation producer(CapturingExecutor executor) {
    return new JdbcOutboxMessageProducerImplementation(
        executor,
        new PostgresMessagingSqlDialect(),
        MessagingSchema.named("tenant_messaging"),
        MessagingTableNames.defaults(),
        HeaderMappedOutboxMessageMapper.withAggregateHeaders(
            "event-aggregate-type", "event-aggregate-id"),
        new JacksonMessageHeadersCodec());
  }

  private Message eventMessage() {
    return MessageBuilder.withPayload("{\"orderId\":\"order-1\"}")
        .withId(ID)
        .withType("OrderPlaced.v1")
        .withPartitionId("order-1")
        .withMessageDate(CREATED_AT)
        .withHeader(MessageHeaders.LOGICAL_CHANNEL, "order-events")
        .withHeader(MessageHeaders.DESTINATION, "ordering.order-events")
        .withHeader(MessageHeaders.CORRELATION_ID, "checkout-1")
        .withHeader(MessageHeaders.TRACEPARENT, "00-abc-def-01")
        .withHeader("event-type", "OrderPlaced.v1")
        .withHeader("event-aggregate-type", "Order")
        .withHeader("event-aggregate-id", "order-1")
        .withHeader("event-contract-version", "1")
        .build();
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
