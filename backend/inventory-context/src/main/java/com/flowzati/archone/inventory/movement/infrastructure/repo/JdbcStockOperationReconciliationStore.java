package com.flowzati.archone.inventory.movement.infrastructure.repo;

import com.flowzati.archone.inventory.movement.application.repo.StockOperationReconciliationStore;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** PostgreSQL projection of invariants also protected by deferred database constraints. */
@Repository
public class JdbcStockOperationReconciliationStore implements StockOperationReconciliationStore {

    private final JdbcClient jdbcClient;

    public JdbcStockOperationReconciliationStore(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public ReconciliationReport inspect(int sampleLimit) {
        if (sampleLimit <= 0) {
            throw new IllegalArgumentException("Stock operation reconciliation sample limit must be positive");
        }
        return new ReconciliationReport(
                heterogeneousOperationIds(sampleLimit),
                moveLineCoverageMismatchMoveIds(sampleLimit),
                reservedCounterMismatchStockQuantIds(sampleLimit));
    }

    private List<UUID> heterogeneousOperationIds(int sampleLimit) {
        return jdbcClient.sql("""
                SELECT operation.id
                  FROM stock_operations operation
                 WHERE NOT EXISTS (
                           SELECT 1
                             FROM stock_moves move
                            WHERE move.stock_operation_id = operation.id)
                    OR EXISTS (
                           SELECT 1
                             FROM stock_moves move
                            WHERE move.stock_operation_id = operation.id
                              AND move.state <> operation.state)
                 ORDER BY operation.id
                 LIMIT ?
                """).param(sampleLimit).query(UUID.class).list();
    }

    private List<UUID> moveLineCoverageMismatchMoveIds(int sampleLimit) {
        return jdbcClient.sql("""
                SELECT move.id
                  FROM stock_moves move
                  LEFT JOIN stock_move_lines move_line ON move_line.move_id = move.id
                 GROUP BY move.id, move.state, move.demand_quantity
                HAVING (move.state IN ('CONFIRMED', 'CANCELLED') AND COUNT(move_line.id) <> 0)
                    OR (move.state IN ('ASSIGNED', 'DONE')
                        AND COALESCE(SUM(move_line.quantity), 0) <> move.demand_quantity)
                 ORDER BY move.id
                 LIMIT ?
                """).param(sampleLimit).query(UUID.class).list();
    }

    private List<UUID> reservedCounterMismatchStockQuantIds(int sampleLimit) {
        return jdbcClient.sql("""
                SELECT stock_quant.id
                  FROM stock_pools stock_quant
                 WHERE stock_quant.reserved_quantity <> COALESCE((
                       SELECT SUM(move_line.quantity)
                         FROM stock_move_lines move_line
                         JOIN stock_moves move ON move.id = move_line.move_id
                        WHERE move_line.stock_pool_id = stock_quant.id
                          AND move.state = 'ASSIGNED'
                   ), 0)
                 ORDER BY stock_quant.id
                 LIMIT ?
                """).param(sampleLimit).query(UUID.class).list();
    }
}
