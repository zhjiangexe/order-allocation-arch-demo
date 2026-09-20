package com.flowzati.archone.inventory.movement.infrastructure.persistence.jdbc.store;

import com.flowzati.archone.inventory.movement.application.result.StockOperationView;
import com.flowzati.archone.inventory.movement.application.store.StockOperationViewStore;
import com.flowzati.archone.inventory.movement.domain.valueobject.StockOperationSource;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** One-statement read adapter; result cardinality changes row count, never query count. */
@Repository
public class JdbcStockOperationViewStore implements StockOperationViewStore {

    private static final String PROJECTION = """
      SELECT operation.id AS operation_id,
             operation.stock_operation_type_id,
             operation.direction,
             operation.owner_id,
             operation.from_location_id,
             operation.to_location_id,
             operation.source_type,
             operation.source_id,
             operation.allocation_unit_key,
             operation.policy_code AS assignment_policy,
             operation.enqueued_at,
             operation.dispatch_by,
             operation.release_priority,
             operation.state AS operation_state,
             move.id AS move_id,
             move.source_line_id,
             move.line_sequence,
             move.sku_code AS move_sku_code,
             move.demand_quantity,
             move.state AS move_state,
             move.created_at,
             move.assigned_at,
             line.id AS move_line_id,
             line.stock_pool_id AS stock_quant_id,
             line.quantity AS reserved_quantity,
             quant.location_id AS quant_location_id,
             quant.sku_code AS quant_sku_code,
             quant.in_date,
             quant.expiry_date
        FROM selected_operation operation
        JOIN stock_moves move ON move.stock_operation_id = operation.id
        LEFT JOIN stock_move_lines line ON line.move_id = move.id
        LEFT JOIN stock_pools quant ON quant.id = line.stock_pool_id
       ORDER BY operation.enqueued_at, operation.id, move.line_sequence, move.id, line.stock_pool_id, line.id
      """;

    private final JdbcClient jdbcClient;

    public JdbcStockOperationViewStore(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public List<StockOperationView> findConfirmedOutbound(int limit) {
        return findConfirmedOutbound(null, limit);
    }

    @Override
    public List<StockOperationView> findConfirmedOutbound(UUID ownerId, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("Confirmed operation query limit must be positive");
        }
        String sql = """
          WITH selected_operation AS (
            SELECT *
              FROM stock_operations
             WHERE state = 'CONFIRMED'
               AND direction = 'OUTBOUND'
               %s
             ORDER BY enqueued_at, id
             LIMIT ?
          )
          """.formatted(ownerId == null ? "" : "AND owner_id = ?") + PROJECTION;
        return ownerId == null ? query(sql, limit) : query(sql, ownerId, limit);
    }

    @Override
    public Optional<StockOperationView> findBySource(StockOperationSource source) {
        if (source == null) {
            throw new IllegalArgumentException("Stock operation source is required");
        }
        String sql = """
          WITH selected_operation AS (
            SELECT *
              FROM stock_operations
             WHERE source_type = ?
               AND source_id = ?
               AND allocation_unit_key = ?
          )
          """ + PROJECTION;
        List<StockOperationView> views =
                query(sql, source.sourceType().name(), source.sourceId(), source.allocationUnitKey());
        if (views.size() > 1) {
            throw new IllegalStateException("Source identity resolved to more than one stock operation");
        }
        return views.stream().findFirst();
    }

    private List<StockOperationView> query(String sql, Object... arguments) {
        StockOperationViewRowAccumulator accumulator = new StockOperationViewRowAccumulator();
        JdbcClient.StatementSpec statement = jdbcClient.sql(sql);
        for (Object argument : arguments) {
            statement = statement.param(argument);
        }
        statement.query(accumulator::add);
        return accumulator.views();
    }
}
