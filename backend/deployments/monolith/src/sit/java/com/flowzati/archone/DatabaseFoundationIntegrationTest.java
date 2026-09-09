package com.flowzati.archone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowzati.archone.testsupport.PostgreSQLTestConfiguration;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
    @DisplayName("Flyway 應套用並驗證所有資料庫 migration")
    void appliesAndValidatesAllMigrations() {
        assertThat(flyway.info().applied()).isNotEmpty();
        assertThat(flyway.info().pending()).isEmpty();
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
        assertThat(tableExists(jdbcTemplate, "owner_allocation_policies")).isTrue();
        assertThat(columnExists(jdbcTemplate, "owner_allocation_policies", "sequence_policy"))
                .isTrue();
    }

    @Test
    @DisplayName("初始 schema 只建立 canonical stock-operation schema metadata 與 deferred guards")
    void exposesOnlyCanonicalStockOperationMetadata() {
        assertThat(tableExists(jdbcTemplate, "stock_operation_types")).isTrue();
        assertThat(tableExists(jdbcTemplate, "stock_operations")).isTrue();
        assertThat(tableExists(jdbcTemplate, "stock_operation_cancellations")).isTrue();
        assertThat(tableExists(jdbcTemplate, "stock_picking_types")).isFalse();
        assertThat(tableExists(jdbcTemplate, "stock_pickings")).isFalse();
        assertThat(tableExists(jdbcTemplate, "stock_picking_cancellation_operations"))
                .isFalse();
        assertThat(columnExists(jdbcTemplate, "stock_operations", "stock_operation_type_id"))
                .isTrue();
        assertThat(columnExists(jdbcTemplate, "stock_moves", "stock_operation_id"))
                .isTrue();
        assertThat(columnExists(jdbcTemplate, "stock_operation_cancellations", "stock_operation_id"))
                .isTrue();
        assertThat(columnExists(jdbcTemplate, "stock_operation_cancellations", "cancellation_operation_id"))
                .isTrue();
        assertThat(columnExists(jdbcTemplate, "wms_shipments", "stock_operation_id"))
                .isTrue();

        List<String> relationshipMetadata = jdbcTemplate.queryForList("""
        SELECT conname
          FROM pg_constraint constraint_metadata
          JOIN pg_class table_metadata ON table_metadata.oid = constraint_metadata.conrelid
         WHERE table_metadata.relname IN (
             'stock_operation_types', 'stock_operations', 'stock_moves',
             'stock_operation_cancellations', 'wms_shipments')
        UNION ALL
        SELECT indexname
          FROM pg_indexes
         WHERE schemaname = current_schema()
           AND tablename IN (
               'stock_operation_types', 'stock_operations', 'stock_moves',
               'stock_operation_cancellations', 'wms_shipments')
        """, String.class);
        assertThat(relationshipMetadata).noneMatch(name -> name.contains("picking"));

        assertThat(jdbcTemplate.queryForList(
                        "SELECT proname FROM pg_proc WHERE pronamespace = current_schema()::regnamespace",
                        String.class))
                .contains("assert_stock_operation", "enforce_stock_operation_group")
                .doesNotContain("assert_stock_picking_group", "enforce_stock_picking_group");
        assertThat(jdbcTemplate.queryForList("""
        SELECT trigger_name
          FROM information_schema.triggers
         WHERE trigger_schema = current_schema()
           AND event_object_table IN ('stock_operations', 'stock_moves', 'stock_move_lines')
        """, String.class))
                .contains(
                        "ck_stock_operations_move_group",
                        "ck_stock_moves_operation_group",
                        "ck_stock_move_lines_operation_group")
                .noneMatch(name -> name.contains("picking"));
    }

    @Test
    @DisplayName("交易失敗時應回滾資料庫變更")
    void rollsBackDatabaseChangesWhenTransactionFails() {
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
                    jdbcTemplate.execute("CREATE TABLE " + ROLLBACK_PROBE_TABLE + " (id INTEGER PRIMARY KEY)");
                    jdbcTemplate.update("INSERT INTO " + ROLLBACK_PROBE_TABLE + " (id) VALUES (1)");
                    throw new IllegalStateException("force rollback");
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("force rollback");

        Integer tableCount = jdbcTemplate.queryForObject("""
        SELECT COUNT(*)
        FROM information_schema.tables
        WHERE table_schema = 'public'
          AND table_name = ?
        """, Integer.class, ROLLBACK_PROBE_TABLE);

        assertThat(tableCount).isZero();
    }

    @Test
    @DisplayName("整理後的初始 schema 重跑 Flyway 不應執行任何 migration")
    void freshBaselineIsIdempotent() {
        assertThat(flyway.info().applied()).hasSize(6);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
        for (String legacyTable :
                List.of("allocation_demands", "allocation_demand_lines", "allocations", "allocation_slices")) {
            assertThat(tableExists(jdbcTemplate, legacyTable)).as(legacyTable).isFalse();
        }
    }

    private static boolean tableExists(JdbcTemplate staged, String tableName) {
        return Boolean.TRUE.equals(
                staged.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class, tableName));
    }

    private static boolean columnExists(JdbcTemplate staged, String tableName, String columnName) {
        return Boolean.TRUE.equals(staged.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                      FROM information_schema.columns
                     WHERE table_schema = current_schema()
                       AND table_name = ?
                       AND column_name = ?)
                """, Boolean.class, tableName, columnName));
    }
}
