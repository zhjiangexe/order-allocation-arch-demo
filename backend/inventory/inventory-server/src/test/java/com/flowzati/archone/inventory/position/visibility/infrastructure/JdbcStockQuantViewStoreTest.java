package com.flowzati.archone.inventory.position.visibility.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowzati.archone.inventory.balance.infrastructure.persistence.jdbc.store.JdbcStockQuantViewStore;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

@DisplayName("JDBC stock quant view Store")
class JdbcStockQuantViewStoreTest {

    @Test
    @DisplayName("executes one narrow ordered projection query")
    void executesOneNarrowOrderedQuery() {
        CountingJdbcTemplate jdbcTemplate = new CountingJdbcTemplate();
        JdbcStockQuantViewStore jdbcStockQuantViewStore = new JdbcStockQuantViewStore(JdbcClient.create(jdbcTemplate));
        UUID ownerId = new UUID(0, 1);
        UUID locationId = new UUID(0, 2);

        assertThat(jdbcStockQuantViewStore.findBatchesInLocation(ownerId, locationId))
                .isEmpty();

        assertThat(jdbcTemplate.statementCount()).isEqualTo(1);
        assertThat(jdbcTemplate.arguments()).containsExactly(ownerId, locationId);
        assertThat(jdbcTemplate.sql()).contains("ORDER BY sku_code, expiry_date, in_date, id");
        String selectedColumns =
                jdbcTemplate.sql().substring(0, jdbcTemplate.sql().indexOf("FROM"));
        assertThat(selectedColumns)
                .contains("id", "sku_code", "in_date", "expiry_date", "on_hand_quantity", "reserved_quantity")
                .doesNotContain("owner_id", "location_id", "version");
    }

    private static final class CountingJdbcTemplate extends JdbcTemplate {

        private int statementCount;
        private String sql;
        private List<Object> arguments;

        @Override
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... arguments) {
            statementCount++;
            this.sql = sql;
            this.arguments = Arrays.asList(arguments);
            return List.of();
        }

        int statementCount() {
            return statementCount;
        }

        String sql() {
            return sql;
        }

        List<Object> arguments() {
            return arguments;
        }
    }
}
