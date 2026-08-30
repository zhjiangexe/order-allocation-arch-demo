package com.flowzati.archone.inventory.movement.visibility.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowzati.archone.inventory.movement.domain.valueobject.StockOperationSource;
import com.flowzati.archone.inventory.movement.infrastructure.persistence.jdbc.store.JdbcStockOperationViewStore;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;

class JdbcStockOperationViewStoreTest {

    @Test
    void everyProjectionReadExecutesOneStatementRegardlessOfRequestedCardinality() {
        CountingJdbcTemplate jdbcTemplate = new CountingJdbcTemplate();
        JdbcStockOperationViewStore jdbcStockOperationViewStore =
                new JdbcStockOperationViewStore(JdbcClient.create(jdbcTemplate));

        assertThat(jdbcStockOperationViewStore.findConfirmedOutbound(1)).isEmpty();
        assertThat(jdbcTemplate.statementCount()).isEqualTo(1);

        assertThat(jdbcStockOperationViewStore.findConfirmedOutbound(200)).isEmpty();
        assertThat(jdbcTemplate.statementCount()).isEqualTo(2);

        assertThat(jdbcStockOperationViewStore.findBySource(
                        StockOperationSource.primaryOrder(new UUID(0, 1).toString())))
                .isEmpty();
        assertThat(jdbcTemplate.statementCount()).isEqualTo(3);
    }

    private static final class CountingJdbcTemplate extends JdbcTemplate {

        private int statementCount;

        @Override
        public void query(PreparedStatementCreator preparedStatementCreator, RowCallbackHandler rowCallbackHandler) {
            statementCount++;
        }

        int statementCount() {
            return statementCount;
        }
    }
}
