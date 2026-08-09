package com.flowzati.archone.messaging.spring.jdbc;

import com.flowzati.archone.messaging.jdbc.JdbcStatementExecutor;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcOperations;

/** Executes pure-module SQL through Spring's transaction-aware {@link JdbcOperations}. */
public final class SpringJdbcStatementExecutor implements JdbcStatementExecutor {

  private final JdbcOperations jdbcOperations;

  public SpringJdbcStatementExecutor(JdbcOperations jdbcOperations) {
    this.jdbcOperations = Objects.requireNonNull(jdbcOperations, "JdbcOperations is required");
  }

  @Override
  public int update(String sql, List<?> arguments) {
    if (sql == null || sql.isBlank() || arguments == null) {
      throw new IllegalArgumentException("SQL and JDBC arguments are required");
    }
    return jdbcOperations.update(sql, arguments.toArray());
  }
}
