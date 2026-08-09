package com.flowzati.archone.messaging.spring.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcOperations;

class SpringJdbcStatementExecutorTest {

  @Test
  void delegatesToSpringJdbcOperationsWithPositionalArguments() {
    JdbcOperations jdbcOperations = mock(JdbcOperations.class);
    when(jdbcOperations.update(eq("INSERT INTO messages (id) VALUES (?)"), any(Object[].class)))
        .thenReturn(1);
    SpringJdbcStatementExecutor executor = new SpringJdbcStatementExecutor(jdbcOperations);

    int rows = executor.update("INSERT INTO messages (id) VALUES (?)", List.of("message-1"));

    assertThat(rows).isOne();
    verify(jdbcOperations).update(
        eq("INSERT INTO messages (id) VALUES (?)"),
        org.mockito.AdditionalMatchers.aryEq(new Object[]{"message-1"}));
  }
}
