package com.flowzati.archone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowzati.archone.testsupport.PostgreSQLTestConfiguration;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Arrays;

@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@ActiveProfiles("test")
@Import(PostgreSQLTestConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DatabaseFoundationIntegrationTest {

  private static final String ROLLBACK_PROBE_TABLE = "sr08_rollback_probe";

  @Autowired
  private Flyway flyway;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Autowired
  private TransactionTemplate transactionTemplate;

  @BeforeEach
  void removeRollbackProbeIfPresent() {
    jdbcTemplate.execute("DROP TABLE IF EXISTS " + ROLLBACK_PROBE_TABLE);
  }

  @Test
  void appliesAndValidatesAllMigrations() {
    var appliedVersions = Arrays.stream(flyway.info().applied())
        .map(migration -> migration.getVersion().getVersion())
        .toList();

    assertThat(appliedVersions).containsExactly("1", "2");
    assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
  }

  @Test
  void rollsBackDatabaseChangesWhenTransactionFails() {
    assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
      jdbcTemplate.execute("CREATE TABLE " + ROLLBACK_PROBE_TABLE + " (id INTEGER PRIMARY KEY)");
      jdbcTemplate.update("INSERT INTO " + ROLLBACK_PROBE_TABLE + " (id) VALUES (1)");
      throw new IllegalStateException("force rollback");
    })).isInstanceOf(IllegalStateException.class)
        .hasMessage("force rollback");

    Integer tableCount = jdbcTemplate.queryForObject("""
        SELECT COUNT(*)
        FROM information_schema.tables
        WHERE table_schema = 'public'
          AND table_name = ?
        """, Integer.class, ROLLBACK_PROBE_TABLE);

    assertThat(tableCount).isZero();
  }
}
