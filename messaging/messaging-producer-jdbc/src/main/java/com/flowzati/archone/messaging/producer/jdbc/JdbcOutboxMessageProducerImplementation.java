package com.flowzati.archone.messaging.producer.jdbc;

import com.flowzati.archone.messaging.api.Message;
import com.flowzati.archone.messaging.jdbc.JdbcStatementExecutor;
import com.flowzati.archone.messaging.jdbc.MessagingSchema;
import com.flowzati.archone.messaging.jdbc.MessagingSqlDialect;
import com.flowzati.archone.messaging.jdbc.MessagingTableNames;
import com.flowzati.archone.messaging.producer.common.MessageProducerImplementation;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Appends a normalized message through transaction-aware framework-neutral JDBC ports. */
public final class JdbcOutboxMessageProducerImplementation
    implements MessageProducerImplementation {

  private static final List<String> COLUMNS = List.of(
      "id",
      "aggregatetype",
      "aggregateid",
      "type",
      "route",
      "partition_key",
      "payload",
      "timestamp",
      "headers");

  private final JdbcStatementExecutor statementExecutor;
  private final OutboxMessageMapper messageMapper;
  private final MessageHeadersCodec headersCodec;
  private final String insertSql;

  public JdbcOutboxMessageProducerImplementation(
      JdbcStatementExecutor statementExecutor,
      MessagingSqlDialect dialect,
      MessagingSchema schema,
      MessagingTableNames tableNames,
      OutboxMessageMapper messageMapper,
      MessageHeadersCodec headersCodec
  ) {
    this.statementExecutor = Objects.requireNonNull(
        statementExecutor, "JDBC statement executor is required");
    this.messageMapper = Objects.requireNonNull(messageMapper, "Outbox message mapper is required");
    this.headersCodec = Objects.requireNonNull(headersCodec, "Message headers codec is required");
    Objects.requireNonNull(dialect, "Messaging SQL dialect is required");
    Objects.requireNonNull(schema, "Messaging schema is required");
    Objects.requireNonNull(tableNames, "Messaging table names are required");
    this.insertSql = dialect.insert(
        schema.qualify(tableNames.outbox()), COLUMNS, Set.of("payload"));
  }

  @Override
  public void send(String destination, Message message) {
    OutboxMessage outbox = messageMapper.map(destination, message);
    String encodedHeaders = headersCodec.encode(outbox.serializedHeaders());

    List<Object> arguments = new ArrayList<>(COLUMNS.size());
    arguments.add(outbox.messageId());
    arguments.add(outbox.aggregateType().orElse(null));
    arguments.add(outbox.aggregateId().orElse(null));
    arguments.add(outbox.messageType());
    arguments.add(outbox.destination());
    arguments.add(outbox.partitionKey());
    arguments.add(outbox.payload());
    arguments.add(outbox.createdAt().atOffset(ZoneOffset.UTC));
    arguments.add(encodedHeaders);

    int rows = statementExecutor.update(insertSql, arguments);
    if (rows != 1) {
      throw new IllegalStateException("Outbox append affected an unexpected row count: " + rows);
    }
  }
}
