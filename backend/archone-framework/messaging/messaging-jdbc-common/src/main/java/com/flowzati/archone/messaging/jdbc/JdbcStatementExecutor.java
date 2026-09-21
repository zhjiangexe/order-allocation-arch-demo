package com.flowzati.archone.messaging.jdbc;

import java.util.List;

/** Executes a parameterized JDBC update on the transaction-aware connection owned by the adapter. */
@FunctionalInterface
public interface JdbcStatementExecutor {

    int update(String sql, List<?> arguments);
}
