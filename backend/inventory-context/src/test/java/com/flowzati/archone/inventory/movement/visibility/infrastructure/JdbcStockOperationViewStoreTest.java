package com.flowzati.archone.inventory.movement.visibility.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.flowzati.archone.inventory.movement.domain.valueobject.StockOperationSource;
import com.flowzati.archone.inventory.movement.infrastructure.persistence.jdbc.store.JdbcStockOperationViewStore;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;

class JdbcStockOperationViewStoreTest {

    @Test
    void ownerPredicateIsAppliedBeforeTheQueueLimit() throws SQLException {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        JdbcTemplate template = new JdbcTemplate() {
            @Override
            public void query(PreparedStatementCreator creator, RowCallbackHandler handler) {
                try {
                    creator.createPreparedStatement(connection);
                } catch (SQLException exception) {
                    throw new AssertionError(exception);
                }
            }
        };
        UUID ownerId = UUID.randomUUID();
        new JdbcStockOperationViewStore(JdbcClient.create(template)).findConfirmedOutbound(ownerId, 20);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sql.capture());
        assertThat(sql.getValue()).contains("AND owner_id = ?");
        assertThat(sql.getValue().indexOf("AND owner_id = ?"))
                .isLessThan(sql.getValue().indexOf("LIMIT ?"));
        verify(statement).setObject(1, ownerId);
        verify(statement).setObject(2, 20);
    }

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
